package com.jordansimsmith.tcginventory.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.Offer;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.ReleaseUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.ReserveUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.listings.client.FakeListingService;
import com.jordansimsmith.tcginventory.listings.client.Listing;
import com.jordansimsmith.tcginventory.publish.ListingPhaseProcessor;
import com.jordansimsmith.tcginventory.publish.PublishJobProcessor;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@Testcontainers
public class OrderVoidingIntegrationTest {
  private static final String USER = "jordan";
  private static final String SKU_ID = "mtg#scryfall#card-1#normal#NM";
  private static final int LISTING_ID = 101;
  private static final String ORDER_ID = "83663";
  private static final Instant NOW = Instant.ofEpochSecond(1700000000);

  private FakeListingService listingService;
  private FakeClock fakeClock;
  private FakeFetchTcgService fakeFetchTcgService;
  private TcgInventoryTestFactory factory;
  private DynamoDbTable<OrderItem> orderTable;
  private OrderPhaseProcessor orderPhaseProcessor;

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
    listingService = new FakeListingService(factory.inventoryService());
    fakeClock = factory.fakeClock();
    fakeFetchTcgService = factory.fakeFetchTcgService();
    fakeClock.setTime(NOW);
    orderTable = factory.orderTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeFetchTcgService.reset();
    factory.fakeUlidGenerator().reset();

    var orderRepository =
        new OrderRepository(
            orderTable,
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), OrderRowItem.class),
            factory.jobTable(),
            factory.dynamoDbClient(),
            fakeClock);
    orderPhaseProcessor =
        new OrderPhaseProcessor(
            orderTable,
            listingService,
            factory.orderService(),
            orderRepository,
            factory.inventoryService(),
            fakeClock,
            fakeFetchTcgService);
  }

  @Test
  void processShouldRecordAndReleaseCancelledOrder() throws Exception {
    // arrange
    createSku();
    createUnit(1, "reserved", ORDER_ID);
    createOrder(ORDER_ID, "awaiting_payment", List.of(line(List.of(1))));
    seedCancellation(ORDER_ID);

    // act
    orderPhaseProcessor.process(USER);

    // assert
    var order = getOrder(ORDER_ID);
    assertThat(order.getStatus()).isEqualTo("voided");
    assertThat(order.getFetchtcgStatus()).isEqualTo("CANCELLED_BY_SELLER");
    assertThat(order.getFetchtcgCurrentAction()).isNull();

    var unit = getUnit(1);
    assertThat(unit.status()).isEqualTo(UnitStatus.IN_STOCK);

    var sku = getSku();
    assertThat(sku.version()).isEqualTo(2);
    assertThat(
            factory
                .inventoryService()
                .findSkus(
                    new RequestContext(USER), new FindSkusRequest(null, null, true, 100, null))
                .skus())
        .extracting(Sku::skuId)
        .contains(SKU_ID);
  }

  @Test
  void processShouldResumePartialReleaseAfterOfferRead() throws Exception {
    // arrange
    createSku();
    createUnit(1, "reserved", ORDER_ID);
    createUnit(2, "sold", "another-order");
    createOrder(ORDER_ID, "voiding", List.of(line(List.of(1, 2))));
    seedCancellation(ORDER_ID);

    // act
    var publishJobProcessor = newPublishJobProcessor();
    var jobItem = JobItem.create(USER, "publish-job", "publish", null, null, NOW);
    var result = publishJobProcessor.processBatch(USER, jobItem);

    // assert
    assertThat(result)
        .isEqualTo(new JobProcessor.FailureJobResult("unit is not reserved for this reservation"));
    assertThat(getOrder(ORDER_ID).getStatus()).isEqualTo("voiding");
    assertThat(getUnit(1).status()).isEqualTo(UnitStatus.IN_STOCK);
    assertThat(getUnit(1).reservationId()).isNull();

    // arrange retry after repairing the conflicting saved allocation
    seedUnit(2, "reserved", ORDER_ID);

    // act
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder(ORDER_ID).getStatus()).isEqualTo("voided");
    assertThat(getUnit(2).status()).isEqualTo(UnitStatus.IN_STOCK);
    assertThat(getUnit(2).reservationId()).isNull();
    assertThat(getSku().version()).isEqualTo(3);
  }

  @Test
  void processShouldReleaseDuplicateAllocationsOnlyOnce() throws Exception {
    // arrange
    createSku();
    createUnit(1, "reserved", ORDER_ID);
    createOrder(ORDER_ID, "awaiting_payment", List.of(line(List.of(1)), line(List.of(1))));
    seedCancellation(ORDER_ID);

    // act
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder(ORDER_ID).getStatus()).isEqualTo("voided");
    assertThat(getUnit(1).status()).isEqualTo(UnitStatus.IN_STOCK);
    assertThat(getUnit(1).reservationId()).isNull();
    assertThat(getSku().version()).isEqualTo(2);
  }

  @Test
  void processShouldPreserveForeignAllocationAfterSavingVoiding() throws Exception {
    // arrange
    createSku();
    createUnit(1, "reserved", ORDER_ID);
    var secondOrderId = "83664";
    createOrder(secondOrderId, "awaiting_payment", List.of(line(List.of(1))));
    seedCancellation(secondOrderId);

    // act
    assertThatThrownBy(() -> orderPhaseProcessor.process(USER))
        .isInstanceOf(ConflictException.class)
        .hasMessage("unit is not reserved for this reservation");

    // assert
    assertThat(getOrder(secondOrderId).getStatus()).isEqualTo("voiding");
    assertThat(getUnit(1).reservationId()).isEqualTo(ORDER_ID);
  }

  @Test
  void processShouldFinalizeAlreadyReleasedAndEmptyVoidingOrdersOnce() throws Exception {
    // arrange
    createSku();
    createUnit(1, "reserved", ORDER_ID);
    createOrder(ORDER_ID, "voiding", List.of(line(List.of(1))));
    seedCancellation(ORDER_ID);
    factory
        .inventoryService()
        .releaseUnit(new RequestContext(USER), new ReleaseUnitRequest(SKU_ID, 1, ORDER_ID));

    // act
    orderPhaseProcessor.process(USER);
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder(ORDER_ID).getStatus()).isEqualTo("voided");
    assertThat(getSku().version()).isEqualTo(2);

    // arrange empty cancellation
    var emptyOrderId = "83664";
    createOrder(emptyOrderId, "awaiting_payment", List.of());
    seedCancellation(ORDER_ID, emptyOrderId);

    // act
    orderPhaseProcessor.process(USER);
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder(emptyOrderId).getStatus()).isEqualTo("voided");
  }

  @Test
  void processShouldNotReleaseAnotherReservationOnReplay() throws Exception {
    // arrange
    createSku();
    createUnit(1, "reserved", ORDER_ID);
    createOrder(ORDER_ID, "voiding", List.of(line(List.of(1))));
    seedCancellation(ORDER_ID);
    var ctx = new RequestContext(USER);
    factory.inventoryService().releaseUnit(ctx, new ReleaseUnitRequest(SKU_ID, 1, ORDER_ID));
    factory
        .inventoryService()
        .reserveUnit(ctx, new ReserveUnitRequest("another-allocation", "another-order", SKU_ID));

    // act
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder(ORDER_ID).getStatus()).isEqualTo("voided");
    assertThat(getUnit(1).status()).isEqualTo(UnitStatus.RESERVED);
    assertThat(getUnit(1).reservationId()).isEqualTo("another-order");
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
    listingService.seedListing(USER, new Listing(LISTING_ID, SKU_ID));
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

  private void createOrder(String orderId, String status, List<OrderRowItem> lines) {
    orderTable.putItem(
        createOrderItem(
            USER,
            orderId,
            status,
            "ACCEPTED",
            null,
            "PICKUP",
            null,
            null,
            null,
            "2.00",
            lines,
            NOW));
  }

  private OrderRowItem line(List<Integer> sequenceNumbers) {
    return new OrderRowItem(SKU_ID, 1, sequenceNumbers.size(), "2.00", "2.00", sequenceNumbers);
  }

  private void seedCancellation(String... orderIds) {
    var offers = new ArrayList<Offer>();
    for (var orderId : orderIds) {
      offers.add(
          new Offer(
              Long.parseLong(orderId),
              "CANCELLED_BY_SELLER",
              null,
              null,
              "PICKUP",
              null,
              null,
              null,
              new BigDecimal("2.00"),
              List.of()));
    }
    factory.fakeFetchTcgService().seedOffers(USER, offers);
  }

  private PublishJobProcessor newPublishJobProcessor() {
    var dynamoDbClient = factory.dynamoDbClient();
    var orderRepository =
        new OrderRepository(
            orderTable,
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), OrderRowItem.class),
            factory.jobTable(),
            dynamoDbClient,
            fakeClock);
    var orderPhaseProcessor =
        new OrderPhaseProcessor(
            orderTable,
            listingService,
            factory.orderService(),
            orderRepository,
            factory.inventoryService(),
            fakeClock,
            fakeFetchTcgService);
    var listingPhaseProcessor =
        new ListingPhaseProcessor(factory.inventoryService(), factory.listingService());
    return new PublishJobProcessor(orderPhaseProcessor, listingPhaseProcessor);
  }

  private OrderItem getOrder(String orderId) {
    var key =
        Key.builder()
            .partitionValue(OrderItem.formatPk(USER))
            .sortValue(OrderItem.formatSk(orderId))
            .build();
    return orderTable.getItem(request -> request.key(key).consistentRead(true));
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
}
