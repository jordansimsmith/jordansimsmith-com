package com.jordansimsmith.tcginventory.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.tcginventory.AuditItem;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.fetchtcg.FakeFetchTcgClient;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgClient;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.tcginventory.publish.PublishJobProcessor;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.net.URI;
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
  private FakeFetchTcgClient fakeFetchTcgClient;
  private TcgInventoryTestFactory factory;
  private DynamoDbTable<SkuItem> skuTable;
  private DynamoDbTable<UnitItem> unitTable;
  private DynamoDbTable<OrderItem> orderTable;
  private DynamoDbTable<AuditItem> auditTable;
  private OrderPhaseProcessor orderPhaseProcessor;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static final URI UNUSED_S3_ENDPOINT = URI.create("http://localhost:1");

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
  }

  @BeforeEach
  void setUp() {
    factory = TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);
    fakeClock = factory.fakeClock();
    fakeFetchTcgClient = factory.fakeFetchTcgClient();
    fakeClock.setTime(NOW);
    skuTable = factory.skuTable();
    unitTable = factory.unitTable();
    orderTable = factory.orderTable();
    auditTable = factory.auditTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeFetchTcgClient.reset();
    factory.fakeUlidGenerator().reset();

    var inventoryRepository =
        new InventoryRepository(
            unitTable, factory.dynamoDbClient(), fakeClock, factory.fakeUlidGenerator());
    var orderRepository =
        new OrderRepository(orderTable, inventoryRepository, factory.dynamoDbClient(), fakeClock);
    orderPhaseProcessor =
        new OrderPhaseProcessor(
            orderTable,
            skuTable,
            factory.settingsTable(),
            orderRepository,
            inventoryRepository,
            fakeClock,
            fakeFetchTcgClient);
  }

  @Test
  void processShouldAllocateDistinctUnitsAcrossOffersAndLines() {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    createUnit(2, "in_stock", null);
    createUnit(3, "in_stock", null);
    createUnit(4, "in_stock", null);
    fakeFetchTcgClient.seedSellerOffers(
        List.of(
            offer(
                83663,
                "AWAITING_PAYMENT",
                List.of(
                    offerItem(LISTING_ID, 1, "1.10", "2.00"),
                    offerItem(LISTING_ID, 1, "1.20", "2.00"))),
            offer(83664, "SEND_TRACKING_CODE", List.of(offerItem(LISTING_ID, 2, "2.50", "2.00")))));

    // act
    orderPhaseProcessor.process(USER, "token");

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
  void processShouldFailPublishForUnmappedListingBeforeWritingOrders() {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    fakeFetchTcgClient.seedSellerOffers(
        List.of(offer(83663, null, List.of(offerItem(999, 1, "1.00", "2.00")))));

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
    assertThat(fakeFetchTcgClient.getUpsertCalls()).isEmpty();
  }

  @Test
  void processShouldFailPublishForEmptyOfferBeforeWritingOrders() {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    fakeFetchTcgClient.seedSellerOffers(List.of(offer(83663, null, List.of())));

    // act
    var result = processPublish();

    // assert
    assertThat(result)
        .isEqualTo(
            new JobProcessor.FailureJobResult("Offer 83663 has no card lines; check FetchTCG"));
    assertThat(getOrders()).isEmpty();
    assertThat(getUnit(1).getStatus()).isEqualTo("in_stock");
    assertThat(getAudits()).isEmpty();
    assertThat(fakeFetchTcgClient.getUpsertCalls()).isEmpty();
  }

  @Test
  void processShouldPreflightStockAcrossAllOffersBeforeWritingAnyOrder() {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    createUnit(2, "in_stock", null);
    fakeFetchTcgClient.seedSellerOffers(
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
    assertThat(fakeFetchTcgClient.getUpsertCalls()).isEmpty();
  }

  @Test
  void processShouldResumeSavedReservationBeforeFetchingOffers() {
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
    assertThatThrownBy(() -> orderPhaseProcessor.process(USER, "token"))
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
    orderPhaseProcessor.process(USER, "token");

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
  void processShouldRejectDuplicateUnitsInSavedReservation() {
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
    assertThatThrownBy(() -> orderPhaseProcessor.process(USER, "token"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("order allocation contains a duplicate unit");

    // assert
    assertThat(getOrder("83663").getStatus()).isEqualTo("reserving");
    assertThat(getUnit(1).getStatus()).isEqualTo("in_stock");
    assertThat(getAudits()).isEmpty();
  }

  private JobProcessor.JobResult processPublish() {
    return new PublishJobProcessor(factory)
        .processBatch(USER, JobItem.create(USER, "publish-job", "publish", null, NOW));
  }

  private FetchTcgClient.SellerOffer offer(
      int orderId, String currentAction, List<FetchTcgClient.OfferItem> items) {
    return new FetchTcgClient.SellerOffer(
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

  private FetchTcgClient.OfferItem offerItem(
      int listingId, int quantity, String price, String listedPrice) {
    return new FetchTcgClient.OfferItem(
        new FetchTcgClient.OfferListing(listingId, "NM", new BigDecimal(listedPrice)),
        quantity,
        new BigDecimal(price));
  }

  private void createSku() {
    var sku =
        SkuItem.create(
            USER,
            SKU_ID,
            "mtg",
            "scryfall",
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
