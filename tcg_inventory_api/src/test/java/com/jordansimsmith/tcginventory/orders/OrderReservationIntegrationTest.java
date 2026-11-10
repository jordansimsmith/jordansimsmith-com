package com.jordansimsmith.tcginventory.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.Offer;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferItem;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferListing;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.listings.client.UpdateListingRequest;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrdersRequest;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

@Testcontainers
public class OrderReservationIntegrationTest {
  private static final String USER = "jordan";
  private static final String SKU_ID = "mtg#scryfall#card-1#normal#NM";
  private static final int LISTING_ID = 101;
  private static final Instant NOW = Instant.ofEpochSecond(1700000000);

  private FakeClock fakeClock;
  private FakeFetchTcgService fakeFetchTcgService;
  private TcgInventoryTestFactory factory;
  private DynamoDbTable<OrderItem> orderTable;
  private OrderService orderService;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
  }

  @BeforeEach
  void setUp() {
    factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    fakeClock = factory.fakeClock();
    fakeFetchTcgService = factory.fakeFetchTcgService();
    fakeClock.setTime(NOW);
    orderTable = factory.orderTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeFetchTcgService.reset();
    factory.fakeUlidGenerator().reset();

    orderService = factory.orderService();
  }

  @Test
  void processShouldAllocateDistinctUnitsAcrossOffersAndLines() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    createUnit(2, "in_stock", null);
    createUnit(3, "in_stock", null);
    createUnit(4, "in_stock", null);
    factory
        .fakeFetchTcgService()
        .seedOffers(
            USER,
            List.of(
                offer(
                    83663,
                    "AWAITING_PAYMENT",
                    List.of(
                        offerItem(LISTING_ID, 1, "1.10", "2.00"),
                        offerItem(LISTING_ID, 1, "1.20", "2.00"))),
                offer(
                    83664,
                    "SEND_TRACKING_CODE",
                    List.of(offerItem(LISTING_ID, 2, "2.50", "2.00")))));

    // act
    orderService.updateOrders(new RequestContext(USER), new UpdateOrdersRequest());

    // assert
    var firstOrder = getOrder("83663");
    var secondOrder = getOrder("83664");
    assertThat(firstOrder.getStatus()).isEqualTo("awaiting_payment");
    assertThat(findOrderRows(firstOrder))
        .extracting(OrderRowItem::getAllocatedSequenceNumbers)
        .containsExactly(List.of(1), List.of(2));
    assertThat(findOrderRows(firstOrder))
        .extracting(OrderRowItem::getPrice)
        .containsExactly("1.10", "1.20");
    assertThat(findOrderRows(firstOrder))
        .extracting(OrderRowItem::getListedPrice)
        .containsExactly("2.00", "2.00");
    assertThat(secondOrder.getStatus()).isEqualTo("to_pick");
    assertThat(findOrderRows(secondOrder).get(0).getAllocatedSequenceNumbers())
        .containsExactly(3, 4);
    assertThat(secondOrder.getFetchtcgCurrentAction()).isEqualTo("SEND_TRACKING_CODE");
    assertThat(List.of(getUnit(1), getUnit(2), getUnit(3), getUnit(4)))
        .allSatisfy(
            unit -> {
              assertThat(unit.status()).isEqualTo(UnitStatus.RESERVED);
            });
    assertThat(getSku().version()).isEqualTo(5);
  }

  @Test
  void processShouldPreserveEarlierOrdersWhenLaterOffersHaveInsufficientStock() {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    var offers = new ArrayList<Offer>();
    for (int i = 0; i < 99; i++) {
      offers.add(
          new Offer(
              90000 + i, "COMPLETED", null, null, "PICKUP", null, null, null, null, List.of()));
    }
    offers.add(offer(83663, null, List.of(offerItem(LISTING_ID, 1, "1.00", "2.00"))));
    offers.add(offer(83664, null, List.of(offerItem(LISTING_ID, 1, "1.00", "2.00"))));
    factory.fakeFetchTcgService().seedOffers(USER, offers);

    // act & assert
    assertThatThrownBy(
            () -> orderService.updateOrders(new RequestContext(USER), new UpdateOrdersRequest()))
        .isInstanceOf(ConflictException.class)
        .hasMessage("No in-stock units are available for this SKU");
    assertThat(getOrders()).hasSize(2);
    assertThat(getUnit(1).status()).isEqualTo(UnitStatus.RESERVED);
  }

  @Test
  void processShouldFailPublishForUnmappedListingBeforeWritingOrders() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    factory
        .fakeFetchTcgService()
        .seedOffers(USER, List.of(offer(83663, null, List.of(offerItem(999, 1, "1.00", "2.00")))));

    // act
    assertThatThrownBy(this::updateOrders)
        .isInstanceOf(ConflictException.class)
        .hasMessage("Offer 83663 contains listing 999 with no matching inventory SKU");

    // assert
    assertThat(getOrders()).isEmpty();
    assertThat(getUnit(1).status()).isEqualTo(UnitStatus.IN_STOCK);
  }

  @Test
  void processShouldFailPublishForEmptyOfferBeforeWritingOrders() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    factory.fakeFetchTcgService().seedOffers(USER, List.of(offer(83663, null, List.of())));

    // act
    assertThatThrownBy(this::updateOrders)
        .isInstanceOf(ConflictException.class)
        .hasMessage("Offer 83663 has no card lines; check FetchTCG");

    // assert
    assertThat(getOrders()).isEmpty();
    assertThat(getUnit(1).status()).isEqualTo(UnitStatus.IN_STOCK);
  }

  @Test
  void processShouldPreserveReservationsWhenLaterOrderHasInsufficientStock() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    createUnit(2, "in_stock", null);
    factory
        .fakeFetchTcgService()
        .seedOffers(
            USER,
            List.of(
                offer(83663, null, List.of(offerItem(LISTING_ID, 2, "2.00", "2.00"))),
                offer(83664, null, List.of(offerItem(LISTING_ID, 2, "2.00", "2.00")))));

    // act
    assertThatThrownBy(this::updateOrders)
        .isInstanceOf(ConflictException.class)
        .hasMessage("No in-stock units are available for this SKU");

    // assert
    assertThat(getOrders()).hasSize(2);
    assertThat(getUnit(1).status()).isEqualTo(UnitStatus.RESERVED);
    assertThat(getUnit(2).status()).isEqualTo(UnitStatus.RESERVED);
  }

  @Test
  void processShouldResumePartialReservationAfterStockIsRepaired() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    createUnit(2, "sold", "another-order");
    createOrder(
        createOrderItem(
            USER,
            "83663",
            "reserving",
            "ACCEPTED",
            "SEND_REVIEW",
            "PICKUP",
            null,
            null,
            null,
            "4.00",
            List.of(new OrderRowItem(SKU_ID, LISTING_ID, 2, "4.00", "2.00", new ArrayList<>())),
            NOW));

    fakeFetchTcgService.seedOffers(USER, List.of(offer(83663, null, List.of())));

    // act
    assertThatThrownBy(
            () -> orderService.updateOrders(new RequestContext(USER), new UpdateOrdersRequest()))
        .isInstanceOf(ConflictException.class);

    // assert
    assertThat(getOrder("83663").getStatus()).isEqualTo("reserving");
    assertThat(getUnit(1).status()).isEqualTo(UnitStatus.RESERVED);
    assertThat(getUnit(1).reservationId()).isEqualTo("83663");

    // arrange retry after repairing the conflicting saved allocation
    seedUnit(2, "in_stock", null);

    // act
    orderService.updateOrders(new RequestContext(USER), new UpdateOrdersRequest());

    // assert
    assertThat(getOrder("83663").getStatus()).isEqualTo("to_pick");
    assertThat(getUnit(2).status()).isEqualTo(UnitStatus.RESERVED);
    assertThat(getUnit(2).reservationId()).isEqualTo("83663");
    assertThat(getSku().version()).isEqualTo(3);
  }

  @Test
  void processShouldRejectDuplicateUnitsInSavedReservation() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    createOrder(
        createOrderItem(
            USER,
            "83663",
            "reserving",
            "ACCEPTED",
            null,
            "PICKUP",
            null,
            null,
            null,
            "2.00",
            List.of(
                new OrderRowItem(SKU_ID, LISTING_ID, 1, "1.00", "2.00", List.of(1)),
                new OrderRowItem(SKU_ID, LISTING_ID, 1, "1.00", "2.00", List.of(1))),
            NOW));

    fakeFetchTcgService.seedOffers(USER, List.of(offer(83663, null, List.of())));

    // act
    assertThatThrownBy(
            () -> orderService.updateOrders(new RequestContext(USER), new UpdateOrdersRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("order allocation contains a duplicate unit");

    // assert
    assertThat(getOrder("83663").getStatus()).isEqualTo("reserving");
    assertThat(getUnit(1).status()).isEqualTo(UnitStatus.IN_STOCK);
  }

  @Test
  void processShouldResolveHistoricalAssociationsForTheCurrentUser() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    synchronizeListing(USER, 202);
    factory
        .fakeInventoryService()
        .seedSku(
            "another-user",
            new Sku(
                SKU_ID,
                "mtg",
                "card-1",
                "Card",
                "dom",
                "Dominaria",
                "1",
                "normal",
                Condition.NM,
                new BigDecimal("2.00"),
                "fetchtcg-card-1",
                2,
                1),
            false);
    factory
        .fakeInventoryService()
        .seedUnits(
            "another-user",
            SKU_ID,
            List.of(
                new Unit(
                    1, "A0-1", UnitStatus.IN_STOCK, List.of(), NOW.getEpochSecond(), null, null)));
    synchronizeListing("another-user", 999);
    fakeFetchTcgService.seedOffers(
        USER, List.of(offer(83663, null, List.of(offerItem(LISTING_ID, 1, "1.00", "2.00")))));

    // act
    orderService.updateOrders(new RequestContext(USER), new UpdateOrdersRequest());

    // assert
    assertThat(getUnit(1).status()).isEqualTo(UnitStatus.RESERVED);
    fakeFetchTcgService.seedOffers(
        USER, List.of(offer(83664, null, List.of(offerItem(999, 1, "1.00", "2.00")))));
    assertThatThrownBy(
            () -> orderService.updateOrders(new RequestContext(USER), new UpdateOrdersRequest()))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("listing 999 with no matching inventory SKU");
  }

  @Test
  void processShouldReserveAnOrderExceedingOneHundredUnits() throws Exception {
    // arrange
    createSku();
    for (int sequence = 0; sequence < 101; sequence++) {
      createUnit(sequence, "in_stock", null);
    }
    fakeFetchTcgService.seedOffers(
        USER, List.of(offer(83663, null, List.of(offerItem(LISTING_ID, 101, "202.00", "2.00")))));

    // act
    orderService.updateOrders(new RequestContext(USER), new UpdateOrdersRequest());
    orderService.updateOrders(new RequestContext(USER), new UpdateOrdersRequest());

    // assert
    assertThat(getOrder("83663").getStatus()).isEqualTo("awaiting_payment");
    assertThat(findOrderRows(getOrder("83663")).getFirst().getAllocatedSequenceNumbers())
        .containsExactlyElementsOf(IntStream.range(0, 101).boxed().toList());
  }

  private void updateOrders() throws Exception {
    orderService.updateOrders(new RequestContext(USER), new UpdateOrdersRequest());
  }

  private Offer offer(int orderId, String currentAction, List<OfferItem> items) {
    return new Offer(
        orderId,
        "ACCEPTED",
        currentAction,
        null,
        "PICKUP",
        null,
        null,
        null,
        new BigDecimal("4.00"),
        items);
  }

  private OfferItem offerItem(int listingId, int quantity, String price, String listedPrice) {
    return new OfferItem(
        new OfferListing(listingId, "NM", new BigDecimal(listedPrice)),
        quantity,
        new BigDecimal(price));
  }

  private void createSku() {
    factory
        .fakeInventoryService()
        .seedSku(
            USER,
            new Sku(
                SKU_ID,
                "mtg",
                "card-1",
                "Card",
                "dom",
                "Dominaria",
                "1",
                "normal",
                Condition.NM,
                new BigDecimal("2.00"),
                "fetchtcg-card-1",
                2,
                1),
            false);
  }

  private void createUnit(int sequenceNumber, String status, String orderId) {
    var units = new ArrayList<>(getUnits());
    units.add(
        new Unit(
            sequenceNumber,
            "A0-" + sequenceNumber,
            UnitStatus.valueOf(status.toUpperCase()),
            List.of(),
            NOW.getEpochSecond(),
            null,
            orderId));
    factory.fakeInventoryService().seedUnits(USER, SKU_ID, units);
    if ("in_stock".equals(status)) {
      synchronizeListing(USER, LISTING_ID);
    }
  }

  private void synchronizeListing(String user, int listingId) {
    fakeFetchTcgService.seedListingId(user, "fetchtcg-card-1", "raw-nm", listingId);
    try {
      factory
          .listingService()
          .updateListing(new RequestContext(user), new UpdateListingRequest(SKU_ID));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void seedUnit(int sequenceNumber, String status, String orderId) {
    var units = new ArrayList<>(getUnits());
    units.replaceAll(
        unit ->
            unit.sequenceNumber() == sequenceNumber
                ? new Unit(
                    unit.sequenceNumber(),
                    unit.location(),
                    UnitStatus.valueOf(status.toUpperCase()),
                    unit.photos(),
                    unit.createdAt(),
                    unit.updatedAt(),
                    orderId)
                : unit);
    factory.fakeInventoryService().seedUnits(USER, SKU_ID, units);
  }

  private void createOrder(OrderItem order) {
    orderTable.putItem(order);
  }

  private OrderItem getOrder(String orderId) {
    var key =
        Key.builder()
            .partitionValue(OrderItem.formatPk(USER))
            .sortValue(OrderItem.formatSk(orderId))
            .build();
    return orderTable.getItem(request -> request.key(key).consistentRead(true));
  }

  private List<OrderItem> getOrders() {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(OrderItem.formatPk(USER))
                        .sortValue(OrderItem.ORDER_PREFIX)
                        .build()))
            .consistentRead(true)
            .build();
    var results = new ArrayList<OrderItem>();
    orderTable.query(request).items().forEach(results::add);
    return results;
  }

  private Unit getUnit(int sequenceNumber) {
    return getUnits().stream()
        .filter(unit -> unit.sequenceNumber() == sequenceNumber)
        .findFirst()
        .orElseThrow();
  }

  private List<Unit> getUnits() {
    try {
      return factory
          .inventoryService()
          .findUnits(new RequestContext(USER), new FindUnitsRequest(SKU_ID, 100, null))
          .units();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Sku getSku() {
    try {
      return factory
          .inventoryService()
          .getSku(new RequestContext(USER), new GetSkuRequest(SKU_ID))
          .sku();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private OrderItem createOrderItem(
      String user,
      String orderId,
      String status,
      String providerStatus,
      String action,
      String delivery,
      String buyer,
      OrderItem.BuyerAddress address,
      String postage,
      String total,
      List<OrderRowItem> lines,
      Instant createdAt) {
    var table = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), OrderRowItem.class);
    for (int position = 0; position < lines.size(); position++) {
      var line = lines.get(position);
      table.putItem(
          OrderRowItem.create(
              user,
              orderId,
              position,
              line.getSkuId(),
              line.getFetchtcgListingId(),
              line.getQuantity(),
              line.getPrice(),
              line.getListedPrice(),
              line.getAllocatedSequenceNumbers()));
    }
    return OrderItem.create(
        user,
        orderId,
        status,
        providerStatus,
        action,
        delivery,
        buyer,
        address,
        postage,
        total,
        lines.size(),
        createdAt);
  }

  private List<OrderRowItem> findOrderRows(OrderItem order) {
    return TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), OrderRowItem.class)
        .query(
            request ->
                request
                    .queryConditional(
                        QueryConditional.keyEqualTo(
                            Key.builder()
                                .partitionValue(order.getPk() + "#ORDER#" + order.getOrderId())
                                .build()))
                    .consistentRead(true))
        .items()
        .stream()
        .toList();
  }
}
