package com.jordansimsmith.tcginventory.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindOffersRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindOffersResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.Offer;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferItem;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferListing;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.tcginventory.listings.client.FakeListingService;
import com.jordansimsmith.tcginventory.listings.client.FindListingsRequest;
import com.jordansimsmith.tcginventory.listings.client.FindListingsResponse;
import com.jordansimsmith.tcginventory.listings.client.Listing;
import com.jordansimsmith.tcginventory.listings.client.ListingService;
import com.jordansimsmith.tcginventory.publish.ListingPhaseProcessor;
import com.jordansimsmith.tcginventory.publish.PublishJobProcessor;
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

  private FakeListingService listingService;
  private FakeClock fakeClock;
  private FakeFetchTcgService fakeFetchTcgService;
  private TcgInventoryTestFactory factory;
  private DynamoDbTable<SkuItem> skuTable;
  private DynamoDbTable<UnitItem> unitTable;
  private DynamoDbTable<OrderItem> orderTable;
  private OrderPhaseProcessor orderPhaseProcessor;
  private OrderRepository orderRepository;

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
    skuTable = factory.skuTable();
    unitTable = factory.unitTable();
    orderTable = factory.orderTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeFetchTcgService.reset();
    factory.fakeUlidGenerator().reset();

    orderRepository =
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
  void createReservingOrderShouldCompleteInterruptedStagingForManyRows() {
    // arrange
    var rows = new ArrayList<OrderRowItem>();
    for (int position = 0; position < 101; position++) {
      rows.add(
          OrderRowItem.create(
              USER, "83663", position, SKU_ID, LISTING_ID, 1, "2.00", "3.00", new ArrayList<>()));
    }
    var table = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), OrderRowItem.class);
    table.putItem(rows.getFirst());
    table.putItem(
        OrderRowItem.create(
            USER, "83663", 101, SKU_ID, LISTING_ID, 1, "9.00", null, new ArrayList<>()));
    var order =
        OrderItem.create(
            USER,
            "83663",
            "reserving",
            "ACCEPTED",
            null,
            null,
            null,
            null,
            null,
            null,
            rows.size(),
            NOW);

    // act
    orderRepository.createReservingOrder(order, rows);
    var saved = getOrder("83663");
    var savedRows = orderRepository.findRows(USER, saved);

    // assert
    assertThat(saved.getRowCount()).isEqualTo(101);
    assertThat(savedRows).hasSize(101);
    assertThat(savedRows)
        .extracting(OrderRowItem::getPosition)
        .containsExactlyElementsOf(IntStream.range(0, 101).boxed().toList());
    assertThat(savedRows.getFirst().getUser()).isEqualTo(USER);
    assertThat(savedRows.getFirst().getOrderId()).isEqualTo("83663");
    assertThat(savedRows.getFirst().getPk()).isEqualTo("USER#" + USER + "#ORDER#83663");
    assertThat(savedRows.getFirst().getSk()).isEqualTo("ROW#0000000000");
    assertThat(savedRows.getLast().getPrice()).isEqualTo("2.00");
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
    orderPhaseProcessor.process(USER);

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
              assertThat(unit.getStatus()).isEqualTo("reserved");
              assertThat(unit.getReservationId()).isNotNull();
            });
    assertThat(getSku().getVersion()).isEqualTo(5);
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
    assertThatThrownBy(() -> orderPhaseProcessor.process(USER))
        .isInstanceOf(ConflictException.class)
        .hasMessage("No in-stock units are available for this SKU");
    assertThat(getOrders()).hasSize(2);
    assertThat(getUnit(1).getStatus()).isEqualTo("reserved");
  }

  @Test
  void processShouldNotApplyFirstPageOffersWhenLaterPageFails() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    var fetchTcgService = mock(FetchTcgService.class);
    var context = new RequestContext(USER);
    var firstPage =
        new FindOffersResponse(
            List.of(offer(83663, null, List.of(offerItem(LISTING_ID, 1, "1.00", "2.00")))), "1");
    var failure =
        new ServiceUnavailableException("FetchTCG seller offers are temporarily unavailable");
    when(fetchTcgService.findOffers(context, new FindOffersRequest(100, null)))
        .thenReturn(firstPage);
    when(fetchTcgService.findOffers(context, new FindOffersRequest(100, "1"))).thenThrow(failure);
    var processor =
        new OrderPhaseProcessor(
            orderTable,
            listingService,
            factory.orderService(),
            orderRepository,
            factory.inventoryService(),
            fakeClock,
            fetchTcgService);

    // act & assert
    assertThatThrownBy(() -> processor.process(USER)).isSameAs(failure);
    assertThat(getOrders()).isEmpty();
    assertThat(getUnit(1).getStatus()).isEqualTo("in_stock");
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
    var result = processPublish();

    // assert
    assertThat(result)
        .isEqualTo(
            new JobProcessor.FailureJobResult(
                "Offer 83663 contains listing 999 with no matching inventory SKU"));
    assertThat(getOrders()).isEmpty();
    assertThat(getUnit(1).getStatus()).isEqualTo("in_stock");
  }

  @Test
  void processShouldFailPublishForEmptyOfferBeforeWritingOrders() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    factory.fakeFetchTcgService().seedOffers(USER, List.of(offer(83663, null, List.of())));

    // act
    var result = processPublish();

    // assert
    assertThat(result)
        .isEqualTo(
            new JobProcessor.FailureJobResult("Offer 83663 has no card lines; check FetchTCG"));
    assertThat(getOrders()).isEmpty();
    assertThat(getUnit(1).getStatus()).isEqualTo("in_stock");
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
    var result = processPublish();

    // assert
    assertThat(result)
        .isEqualTo(
            new JobProcessor.FailureJobResult("No in-stock units are available for this SKU"));
    assertThat(getOrders()).hasSize(2);
    assertThat(getUnit(1).getStatus()).isEqualTo("reserved");
    assertThat(getUnit(2).getStatus()).isEqualTo("reserved");
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
    assertThatThrownBy(() -> orderPhaseProcessor.process(USER))
        .isInstanceOf(ConflictException.class);

    // assert
    assertThat(getOrder("83663").getStatus()).isEqualTo("reserving");
    assertThat(getUnit(1).getStatus()).isEqualTo("reserved");
    assertThat(getUnit(1).getReservationId()).isEqualTo("83663");

    // arrange retry after repairing the conflicting saved allocation
    var repairedUnit = getUnit(2);
    repairedUnit.setStatus("in_stock");
    repairedUnit.setReservationId(null);
    unitTable.putItem(repairedUnit);

    // act
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder("83663").getStatus()).isEqualTo("to_pick");
    assertThat(getUnit(2).getStatus()).isEqualTo("reserved");
    assertThat(getUnit(2).getReservationId()).isEqualTo("83663");
    assertThat(getSku().getVersion()).isEqualTo(3);
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
    assertThatThrownBy(() -> orderPhaseProcessor.process(USER))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("order allocation contains a duplicate unit");

    // assert
    assertThat(getOrder("83663").getStatus()).isEqualTo("reserving");
    assertThat(getUnit(1).getStatus()).isEqualTo("in_stock");
  }

  @Test
  void processShouldResolveHistoricalAssociationsForTheCurrentUser() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    listingService.seedListing(USER, new Listing(202, SKU_ID));
    listingService.seedListing("another-user", new Listing(999, SKU_ID));
    fakeFetchTcgService.seedOffers(
        USER, List.of(offer(83663, null, List.of(offerItem(LISTING_ID, 1, "1.00", "2.00")))));

    // act
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getUnit(1).getStatus()).isEqualTo("reserved");
    fakeFetchTcgService.seedOffers(
        USER, List.of(offer(83664, null, List.of(offerItem(999, 1, "1.00", "2.00")))));
    assertThatThrownBy(() -> orderPhaseProcessor.process(USER))
        .isInstanceOf(OrderPhaseProcessor.ReservationPreflightException.class)
        .hasMessageContaining("listing 999 with no matching inventory SKU");
  }

  @Test
  void processShouldCompleteAllAssociationLookupsBeforeApplyingOffers() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    var offers = new ArrayList<Offer>();
    var ids = new ArrayList<Integer>();
    for (int i = 0; i < 101; i++) {
      ids.add(i);
      offers.add(offer(90000 + i, null, List.of(offerItem(i, 1, "1.00", "2.00"))));
    }
    offers.add(offer(91000, null, List.of(offerItem(0, 1, "1.00", "2.00"))));
    offers.add(
        new Offer(92000, "COMPLETED", null, null, "PICKUP", null, null, null, null, List.of()));
    fakeFetchTcgService.seedOffers(USER, offers);
    var listings = mock(ListingService.class);
    var ctx = new RequestContext(USER);
    var firstRequest = new FindListingsRequest(ids.subList(0, 100));
    when(listings.findListings(ctx, firstRequest))
        .thenReturn(
            new FindListingsResponse(
                ids.subList(0, 100).stream().map(id -> new Listing(id, SKU_ID)).toList()));
    var failure = new ServiceUnavailableException("Listings unavailable");
    when(listings.findListings(ctx, new FindListingsRequest(List.of(100)))).thenThrow(failure);
    var processor =
        new OrderPhaseProcessor(
            orderTable,
            listings,
            factory.orderService(),
            orderRepository,
            factory.inventoryService(),
            fakeClock,
            fakeFetchTcgService);

    // act & assert
    assertThatThrownBy(() -> processor.process(USER)).isSameAs(failure);
    assertThat(getOrders()).isEmpty();
    assertThat(getUnit(1).getStatus()).isEqualTo("in_stock");
    verify(listings).findListings(ctx, firstRequest);
    verify(listings).findListings(ctx, new FindListingsRequest(List.of(100)));
    verifyNoMoreInteractions(listings);
  }

  @Test
  void processShouldSkipAssociationLookupsWithoutNewAcceptedOffers() throws Exception {
    // arrange
    fakeFetchTcgService.seedOffers(
        USER,
        List.of(
            new Offer(
                92000, "COMPLETED", null, null, "PICKUP", null, null, null, null, List.of())));
    var listings = mock(ListingService.class);
    var processor =
        new OrderPhaseProcessor(
            orderTable,
            listings,
            factory.orderService(),
            orderRepository,
            factory.inventoryService(),
            fakeClock,
            fakeFetchTcgService);

    // act
    processor.process(USER);

    // assert
    verifyNoMoreInteractions(listings);
  }

  @Test
  void processShouldRecoverReservationCommittedBeforeProgressSave() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    fakeFetchTcgService.seedOffers(
        USER, List.of(offer(83663, null, List.of(offerItem(LISTING_ID, 1, "2.00", "2.00")))));
    var repository = spy(orderRepository);
    doThrow(new IllegalStateException("progress unavailable"))
        .doCallRealMethod()
        .when(repository)
        .recordReservation(USER, "83663", 0, 0, 1);
    var processor =
        new OrderPhaseProcessor(
            orderTable,
            listingService,
            factory.orderService(),
            repository,
            factory.inventoryService(),
            fakeClock,
            fakeFetchTcgService);

    // act
    assertThatThrownBy(() -> processor.process(USER)).hasMessage("progress unavailable");
    var version = getSku().getVersion();
    processor.process(USER);

    // assert
    assertThat(getOrder("83663").getStatus()).isEqualTo("awaiting_payment");
    assertThat(findOrderRows(getOrder("83663")).getFirst().getAllocatedSequenceNumbers())
        .containsExactly(1);
    assertThat(getSku().getVersion()).isEqualTo(version);
  }

  @Test
  void processShouldCompleteReservationBeforeCancellingAfterStockRepair() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    fakeFetchTcgService.seedOffers(
        USER, List.of(offer(83663, null, List.of(offerItem(LISTING_ID, 2, "4.00", "2.00")))));
    var repository = spy(orderRepository);
    doThrow(new IllegalStateException("progress unavailable"))
        .when(repository)
        .recordReservation(USER, "83663", 0, 0, 1);
    var processor =
        new OrderPhaseProcessor(
            orderTable,
            listingService,
            factory.orderService(),
            repository,
            factory.inventoryService(),
            fakeClock,
            fakeFetchTcgService);
    assertThatThrownBy(() -> processor.process(USER)).hasMessage("progress unavailable");
    fakeFetchTcgService.seedOffers(
        USER,
        List.of(
            new Offer(
                83663,
                "CANCELLED_BY_BUYER",
                null,
                null,
                "PICKUP",
                null,
                null,
                null,
                null,
                List.of())));

    // act & assert
    assertThatThrownBy(() -> orderPhaseProcessor.process(USER))
        .isInstanceOf(ConflictException.class);
    assertThat(getOrder("83663").getStatus()).isEqualTo("reserving");
    assertThat(getUnit(1).getStatus()).isEqualTo("reserved");

    // arrange
    createUnit(2, "in_stock", null);

    // act
    orderPhaseProcessor.process(USER);
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder("83663").getStatus()).isEqualTo("voided");
    assertThat(findOrderRows(getOrder("83663")).getFirst().getAllocatedSequenceNumbers())
        .containsExactly(1, 2);
    assertThat(getUnit(1).getStatus()).isEqualTo("in_stock");
    assertThat(getUnit(2).getStatus()).isEqualTo("in_stock");
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
    orderPhaseProcessor.process(USER);
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder("83663").getStatus()).isEqualTo("awaiting_payment");
    assertThat(findOrderRows(getOrder("83663")).getFirst().getAllocatedSequenceNumbers())
        .containsExactlyElementsOf(IntStream.range(0, 101).boxed().toList());
  }

  private JobProcessor.JobResult processPublish() {
    return newPublishJobProcessor()
        .processBatch(USER, JobItem.create(USER, "publish-job", "publish", null, null, NOW));
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
    var sku =
        SkuItem.create(
            USER,
            SKU_ID,
            "mtg",
            "card-1",
            "normal",
            "NM",
            "Card",
            "dom",
            "Dominaria",
            "1",
            "fetchtcg-card-1",
            "2.00");
    listingService.seedListing(USER, new Listing(LISTING_ID, SKU_ID));
    skuTable.putItem(sku);
  }

  private void createUnit(int sequenceNumber, String status, String orderId) {
    var unit = UnitItem.create(USER, "mtg", SKU_ID, sequenceNumber, status, "import-1", NOW);
    unit.setReservationId(orderId);
    unitTable.putItem(unit);
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

  private UnitItem getUnit(int sequenceNumber) {
    var key =
        Key.builder()
            .partitionValue(UnitItem.formatPk(USER, SKU_ID))
            .sortValue(UnitItem.formatSk(sequenceNumber))
            .build();
    return unitTable.getItem(request -> request.key(key).consistentRead(true));
  }

  private SkuItem getSku() {
    var key =
        Key.builder()
            .partitionValue(SkuItem.formatPk(USER, SKU_ID))
            .sortValue(SkuItem.formatSk())
            .build();
    return skuTable.getItem(request -> request.key(key).consistentRead(true));
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
