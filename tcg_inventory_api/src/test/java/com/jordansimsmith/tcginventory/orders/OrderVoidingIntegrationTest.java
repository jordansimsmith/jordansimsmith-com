package com.jordansimsmith.tcginventory.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.tcginventory.AuditItem;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.Offer;
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
public class OrderVoidingIntegrationTest {
  private static final String USER = "jordan";
  private static final String SKU_ID = "mtg#scryfall#card-1#normal#NM";
  private static final String ORDER_ID = "83663";
  private static final Instant NOW = Instant.ofEpochSecond(1700000000);

  private FakeClock fakeClock;
  private FakeFetchTcgService fakeFetchTcgService;
  private TcgInventoryTestFactory factory;
  private DynamoDbTable<SkuItem> skuTable;
  private DynamoDbTable<UnitItem> unitTable;
  private DynamoDbTable<OrderItem> orderTable;
  private DynamoDbTable<AuditItem> auditTable;
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
    fakeClock = factory.fakeClock();
    fakeFetchTcgService = factory.fakeFetchTcgService();
    fakeClock.setTime(NOW);
    skuTable = factory.skuTable();
    unitTable = factory.unitTable();
    orderTable = factory.orderTable();
    auditTable = factory.auditTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeFetchTcgService.reset();
    factory.fakeUlidGenerator().reset();

    var inventoryRepository =
        new InventoryRepository(
            unitTable, factory.dynamoDbClient(), fakeClock, factory.fakeUlidGenerator());
    var orderRepository =
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
    assertThat(unit.getStatus()).isEqualTo("in_stock");
    assertThat(unit.getOrderId()).isNull();

    var sku = getSku();
    assertThat(sku.getVersion()).isEqualTo(2);
    assertThat(sku.getDirty()).isTrue();

    var audits = getAudits();
    assertThat(audits)
        .filteredOn(audit -> "release".equals(audit.getEventType()))
        .singleElement()
        .satisfies(
            audit -> {
              assertThat(audit.getOrderId()).isEqualTo(ORDER_ID);
              assertThat(audit.getSkuId()).isEqualTo(SKU_ID);
              assertThat(audit.getSequenceNumber()).isEqualTo(1);
              assertThat(audit.getBeforeStatus()).isEqualTo("reserved");
              assertThat(audit.getAfterStatus()).isEqualTo("in_stock");
            });
    assertThat(audits).filteredOn(audit -> "order_voided".equals(audit.getEventType())).hasSize(1);
  }

  @Test
  void processShouldResumePartialReleaseBeforeOfferRead() throws Exception {
    // arrange
    createSku();
    createUnit(1, "reserved", ORDER_ID);
    createUnit(2, "sold", "another-order");
    createOrder(ORDER_ID, "voiding", List.of(line(List.of(1, 2))));

    // act
    var publishJobProcessor = newPublishJobProcessor();
    var jobItem = JobItem.create(USER, "publish-job", "publish", null, null, NOW);
    assertThatThrownBy(() -> publishJobProcessor.processBatch(USER, jobItem))
        .isInstanceOf(TransactionCanceledException.class);

    // assert
    assertThat(getOrder(ORDER_ID).getStatus()).isEqualTo("voiding");
    assertThat(getUnit(1).getStatus()).isEqualTo("in_stock");
    assertThat(getUnit(1).getOrderId()).isNull();
    assertThat(getAudits()).filteredOn(audit -> "release".equals(audit.getEventType())).hasSize(1);
    assertThat(getAudits())
        .filteredOn(audit -> "order_voided".equals(audit.getEventType()))
        .isEmpty();

    // arrange retry after repairing the conflicting saved allocation
    var repairedUnit = getUnit(2);
    repairedUnit.setStatus("reserved");
    repairedUnit.setOrderId(ORDER_ID);
    unitTable.putItem(repairedUnit);

    // act
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder(ORDER_ID).getStatus()).isEqualTo("voided");
    assertThat(getUnit(2).getStatus()).isEqualTo("in_stock");
    assertThat(getUnit(2).getOrderId()).isNull();
    assertThat(getSku().getVersion()).isEqualTo(3);
    assertThat(getAudits()).filteredOn(audit -> "release".equals(audit.getEventType())).hasSize(2);
    assertThat(getAudits())
        .filteredOn(audit -> "order_voided".equals(audit.getEventType()))
        .hasSize(1);
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
    assertThat(getUnit(1).getStatus()).isEqualTo("in_stock");
    assertThat(getUnit(1).getOrderId()).isNull();
    assertThat(getSku().getVersion()).isEqualTo(2);
    assertThat(getAudits()).filteredOn(audit -> "release".equals(audit.getEventType())).hasSize(1);
    assertThat(getAudits())
        .filteredOn(audit -> "order_voided".equals(audit.getEventType()))
        .hasSize(1);
  }

  @Test
  void processShouldRejectForeignAllocationBeforeVoiding() throws Exception {
    // arrange
    createSku();
    createUnit(1, "reserved", ORDER_ID);
    var secondOrderId = "83664";
    createOrder(secondOrderId, "awaiting_payment", List.of(line(List.of(1))));
    seedCancellation(secondOrderId);

    // act
    assertThatThrownBy(() -> orderPhaseProcessor.process(USER))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("order has an unexpected allocated unit");

    // assert
    assertThat(getOrder(secondOrderId).getStatus()).isEqualTo("awaiting_payment");
    assertThat(getUnit(1).getOrderId()).isEqualTo(ORDER_ID);
    assertThat(getAudits()).isEmpty();
  }

  @Test
  void processShouldFinalizeAlreadyReleasedAndEmptyVoidingOrdersOnce() throws Exception {
    // arrange
    createSku();
    createUnit(1, "in_stock", null);
    createOrder(ORDER_ID, "voiding", List.of(line(List.of(1))));

    // act
    orderPhaseProcessor.process(USER);
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder(ORDER_ID).getStatus()).isEqualTo("voided");
    assertThat(getSku().getVersion()).isEqualTo(1);
    assertThat(getAudits()).filteredOn(audit -> "release".equals(audit.getEventType())).isEmpty();
    assertThat(getAudits())
        .filteredOn(audit -> "order_voided".equals(audit.getEventType()))
        .hasSize(1);

    // arrange empty cancellation
    var emptyOrderId = "83664";
    createOrder(emptyOrderId, "awaiting_payment", List.of());
    seedCancellation(emptyOrderId);

    // act
    orderPhaseProcessor.process(USER);
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder(emptyOrderId).getStatus()).isEqualTo("voided");
    assertThat(getAudits())
        .filteredOn(audit -> "order_voided".equals(audit.getEventType()))
        .hasSize(2);
  }

  private void createSku() {
    skuTable.putItem(
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
            "2.00"));
  }

  private void createUnit(int sequenceNumber, String status, String orderId) {
    var unit = UnitItem.create(USER, "mtg", SKU_ID, sequenceNumber, status, "import-1", NOW);
    unit.setOrderId(orderId);
    unitTable.putItem(unit);
  }

  private void createOrder(String orderId, String status, List<OrderItem.OrderLine> lines) {
    orderTable.putItem(
        OrderItem.create(
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

  private OrderItem.OrderLine line(List<Integer> sequenceNumbers) {
    return new OrderItem.OrderLine(
        SKU_ID, 1, sequenceNumbers.size(), "2.00", "2.00", sequenceNumbers);
  }

  private void seedCancellation(String orderId) {
    var offer =
        new Offer(
            Integer.parseInt(orderId),
            "CANCELLED_BY_SELLER",
            null,
            null,
            "PICKUP",
            null,
            null,
            null,
            new BigDecimal("2.00"),
            List.of());
    // replace the fake page so each test controls which local order sees a cancellation
    factory.fakeFetchTcgService().seedOffers(USER, List.of(offer));
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
            factory.inventoryService(),
            dynamoDbClient,
            fakeClock,
            fakeFetchTcgService,
            factory.photosService());
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
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(AuditItem.formatPk(USER)).build()))
            .build();
    var result = new ArrayList<AuditItem>();
    auditTable.query(request).items().forEach(result::add);
    return result;
  }
}
