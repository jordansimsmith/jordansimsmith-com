package com.jordansimsmith.tcginventory.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.AuditItem;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.fetchtcg.FakeFetchTcgClient;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindOffersRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindOffersResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.Offer;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferItem;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferListing;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
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
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

@Testcontainers
public class OrderReservationIntegrationTest {
  private static final String USER = "jordan";
  private static final String SKU_ID = "mtg#scryfall#card-1#normal#NM";
  private static final int LISTING_ID = 101;
  private static final Instant NOW = Instant.ofEpochSecond(1700000000);

  private FakeClock fakeClock;
  private FakeFetchTcgService fakeFetchTcgService;
  private FakeFetchTcgClient fakeFetchTcgClient;
  private TcgInventoryTestFactory factory;
  private DynamoDbTable<SkuItem> skuTable;
  private DynamoDbTable<UnitItem> unitTable;
  private DynamoDbTable<OrderItem> orderTable;
  private DynamoDbTable<AuditItem> auditTable;
  private OrderPhaseProcessor orderPhaseProcessor;
  private InventoryRepository inventoryRepository;
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
    fakeClock = factory.fakeClock();
    fakeFetchTcgService = factory.fakeFetchTcgService();
    fakeFetchTcgClient = factory.fakeFetchTcgClient();
    fakeClock.setTime(NOW);
    skuTable = factory.skuTable();
    unitTable = factory.unitTable();
    orderTable = factory.orderTable();
    auditTable = factory.auditTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeFetchTcgService.reset();
    fakeFetchTcgClient.reset();
    factory.fakeUlidGenerator().reset();

    inventoryRepository =
        new InventoryRepository(
            unitTable, factory.dynamoDbClient(), fakeClock, factory.fakeUlidGenerator());
    orderRepository =
        new OrderRepository(
            orderTable,
            factory.jobTable(),
            inventoryRepository,
            factory.dynamoDbClient(),
            fakeClock);
    orderPhaseProcessor =
        new OrderPhaseProcessor(
            orderTable,
            skuTable,
            factory.settingsTable(),
            orderRepository,
            inventoryRepository,
            fakeClock,
            fakeFetchTcgService);
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
    assertThat(firstOrder.getLines())
        .extracting(OrderItem.OrderLine::getAllocatedSequenceNumbers)
        .containsExactly(List.of(1), List.of(2));
    assertThat(firstOrder.getLines())
        .extracting(OrderItem.OrderLine::getPrice)
        .containsExactly("1.10", "1.20");
    assertThat(firstOrder.getLines())
        .extracting(OrderItem.OrderLine::getListedPrice)
        .containsExactly("2.00", "2.00");
    assertThat(secondOrder.getStatus()).isEqualTo("to_pick");
    assertThat(secondOrder.getLines().get(0).getAllocatedSequenceNumbers()).containsExactly(3, 4);
    assertThat(secondOrder.getFetchtcgCurrentAction()).isEqualTo("SEND_TRACKING_CODE");
    assertThat(List.of(getUnit(1), getUnit(2), getUnit(3), getUnit(4)))
        .allSatisfy(
            unit -> {
              assertThat(unit.getStatus()).isEqualTo("reserved");
              assertThat(unit.getOrderId()).isNotNull();
            });
    assertThat(getSku().getVersion()).isEqualTo(5);
    assertThat(getAudits()).filteredOn(audit -> "reserve".equals(audit.getEventType())).hasSize(4);
    assertThat(getAudits())
        .filteredOn(audit -> "order_reserved".equals(audit.getEventType()))
        .hasSize(2);
  }

  @Test
  void processShouldPreflightAcceptedOffersAcrossPagesBeforeReservingAnyUnits() {
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
        .isInstanceOf(OrderPhaseProcessor.ReservationPreflightException.class)
        .hasMessageContaining("Offer 83664 needs 1 units");
    assertThat(getOrders()).isEmpty();
    assertThat(getUnit(1).getStatus()).isEqualTo("in_stock");
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
            skuTable,
            factory.settingsTable(),
            orderRepository,
            inventoryRepository,
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
    assertThat(getAudits()).isEmpty();
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
    assertThat(getAudits()).isEmpty();
  }

  @Test
  void processShouldPreflightStockAcrossAllOffersBeforeWritingAnyOrder() throws Exception {
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
            new JobProcessor.FailureJobResult(
                "Offer 83664 needs 2 units from listing 101 (SKU "
                    + SKU_ID
                    + "); only 0 remain in stock"));
    assertThat(getOrders()).isEmpty();
    assertThat(getUnit(1).getStatus()).isEqualTo("in_stock");
    assertThat(getUnit(2).getStatus()).isEqualTo("in_stock");
    assertThat(getAudits()).isEmpty();
  }

  @Test
  void processShouldResumeSavedReservationBeforeFetchingOffers() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    createUnit(2, "sold", "another-order");
    createOrder(
        OrderItem.create(
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
            List.of(new OrderItem.OrderLine(SKU_ID, LISTING_ID, 2, "4.00", "2.00", List.of(1, 2))),
            NOW));

    // act
    assertThatThrownBy(() -> orderPhaseProcessor.process(USER))
        .isInstanceOf(TransactionCanceledException.class);

    // assert
    assertThat(getOrder("83663").getStatus()).isEqualTo("reserving");
    assertThat(getUnit(1).getStatus()).isEqualTo("reserved");
    assertThat(getUnit(1).getOrderId()).isEqualTo("83663");
    assertThat(getAudits()).filteredOn(audit -> "reserve".equals(audit.getEventType())).hasSize(1);
    assertThat(getAudits())
        .filteredOn(audit -> "order_reserved".equals(audit.getEventType()))
        .isEmpty();

    // arrange retry after repairing the conflicting saved allocation
    var repairedUnit = getUnit(2);
    repairedUnit.setStatus("in_stock");
    repairedUnit.setOrderId(null);
    unitTable.putItem(repairedUnit);

    // act
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder("83663").getStatus()).isEqualTo("to_pick");
    assertThat(getUnit(2).getStatus()).isEqualTo("reserved");
    assertThat(getUnit(2).getOrderId()).isEqualTo("83663");
    assertThat(getSku().getVersion()).isEqualTo(3);
    assertThat(getAudits()).filteredOn(audit -> "reserve".equals(audit.getEventType())).hasSize(2);
    assertThat(getAudits())
        .filteredOn(audit -> "order_reserved".equals(audit.getEventType()))
        .hasSize(1);
  }

  @Test
  void processShouldRejectDuplicateUnitsInSavedReservation() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    createOrder(
        OrderItem.create(
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
                new OrderItem.OrderLine(SKU_ID, LISTING_ID, 1, "1.00", "2.00", List.of(1)),
                new OrderItem.OrderLine(SKU_ID, LISTING_ID, 1, "1.00", "2.00", List.of(1))),
            NOW));

    // act
    assertThatThrownBy(() -> orderPhaseProcessor.process(USER))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("order allocation contains a duplicate unit");

    // assert
    assertThat(getOrder("83663").getStatus()).isEqualTo("reserving");
    assertThat(getUnit(1).getStatus()).isEqualTo("in_stock");
    assertThat(getAudits()).isEmpty();
  }

  private JobProcessor.JobResult processPublish() {
    return newPublishJobProcessor()
        .processBatch(USER, JobItem.create(USER, "publish-job", "publish", null, null, NOW));
  }

  private PublishJobProcessor newPublishJobProcessor() {
    var dynamoDbClient = factory.dynamoDbClient();
    var inventoryRepository =
        new InventoryRepository(unitTable, dynamoDbClient, fakeClock, factory.fakeUlidGenerator());
    var orderRepository =
        new OrderRepository(
            orderTable, factory.jobTable(), inventoryRepository, dynamoDbClient, fakeClock);
    var orderPhaseProcessor =
        new OrderPhaseProcessor(
            orderTable,
            skuTable,
            factory.settingsTable(),
            orderRepository,
            inventoryRepository,
            fakeClock,
            fakeFetchTcgService);
    var listingPhaseProcessor =
        new ListingPhaseProcessor(
            skuTable,
            inventoryRepository,
            dynamoDbClient,
            fakeClock,
            fakeFetchTcgService,
            factory.fetchTcgClient(),
            factory.s3Client());
    return new PublishJobProcessor(
        factory.fetchTcgTokenMinter(), orderPhaseProcessor, listingPhaseProcessor);
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
    sku.setFetchtcgListingId(LISTING_ID);
    skuTable.putItem(sku);
  }

  private void createUnit(int sequenceNumber, String status, String orderId) {
    var unit = UnitItem.create(USER, "mtg", SKU_ID, sequenceNumber, status, "import-1", NOW);
    unit.setOrderId(orderId);
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

  private List<AuditItem> getAudits() {
    var key = Key.builder().partitionValue(AuditItem.formatPk(USER)).build();
    var request =
        QueryEnhancedRequest.builder().queryConditional(QueryConditional.keyEqualTo(key)).build();
    var results = new ArrayList<AuditItem>();
    auditTable.query(request).items().forEach(results::add);
    return results;
  }
}
