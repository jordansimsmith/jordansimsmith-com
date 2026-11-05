package com.jordansimsmith.tcginventory.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.AuditItem;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.Offer;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.tcginventory.inventory.client.ReleaseUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.ReserveUnitRequest;
import com.jordansimsmith.tcginventory.listings.client.FakeListingService;
import com.jordansimsmith.tcginventory.publish.ListingPhaseProcessor;
import com.jordansimsmith.tcginventory.publish.PublishJobProcessor;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Testcontainers
public class OrderVoidingIntegrationTest {
  private static final String USER = "jordan";
  private static final String SKU_ID = "mtg#scryfall#card-1#normal#NM";
  private static final String ORDER_ID = "83663";
  private static final Instant NOW = Instant.ofEpochSecond(1700000000);

  private FakeListingService listingService;
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
    listingService = new FakeListingService(factory.inventoryService());
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
            factory.settingsTable(),
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
    assertThat(unit.getStatus()).isEqualTo("in_stock");
    assertThat(unit.getReservationId()).isNull();

    var sku = getSku();
    assertThat(sku.getVersion()).isEqualTo(2);
    assertThat(sku.getDirty()).isTrue();

    var audits = getAudits();
    assertThat(audits)
        .filteredOn(audit -> "release".equals(audit.getEventType()))
        .singleElement()
        .satisfies(
            audit -> {
              var rawAudit =
                  factory
                      .dynamoDbClient()
                      .getItem(
                          request ->
                              request
                                  .tableName(TcgInventoryTable.TABLE_NAME)
                                  .key(
                                      Map.of(
                                          "pk",
                                          AttributeValue.builder().s(audit.getPk()).build(),
                                          "sk",
                                          AttributeValue.builder().s(audit.getSk()).build()))
                                  .consistentRead(true))
                      .item();
              assertThat(rawAudit.get("reservation_id").s()).isEqualTo(ORDER_ID);
              assertThat(audit.getSkuId()).isEqualTo(SKU_ID);
              assertThat(audit.getSequenceNumber()).isEqualTo(1);
              assertThat(audit.getBeforeStatus()).isEqualTo("reserved");
              assertThat(audit.getAfterStatus()).isEqualTo("in_stock");
            });
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
    assertThat(getUnit(1).getStatus()).isEqualTo("in_stock");
    assertThat(getUnit(1).getReservationId()).isNull();
    assertThat(getAudits()).filteredOn(audit -> "release".equals(audit.getEventType())).hasSize(1);

    // arrange retry after repairing the conflicting saved allocation
    var repairedUnit = getUnit(2);
    repairedUnit.setStatus("reserved");
    repairedUnit.setReservationId(ORDER_ID);
    unitTable.putItem(repairedUnit);

    // act
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder(ORDER_ID).getStatus()).isEqualTo("voided");
    assertThat(getUnit(2).getStatus()).isEqualTo("in_stock");
    assertThat(getUnit(2).getReservationId()).isNull();
    assertThat(getSku().getVersion()).isEqualTo(3);
    assertThat(getAudits()).filteredOn(audit -> "release".equals(audit.getEventType())).hasSize(2);
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
    assertThat(getUnit(1).getReservationId()).isNull();
    assertThat(getSku().getVersion()).isEqualTo(2);
    assertThat(getAudits()).filteredOn(audit -> "release".equals(audit.getEventType())).hasSize(1);
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
    assertThat(getUnit(1).getReservationId()).isEqualTo(ORDER_ID);
    assertThat(getAudits()).isEmpty();
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
    var auditsBefore = getAudits().size();

    // act
    orderPhaseProcessor.process(USER);
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder(ORDER_ID).getStatus()).isEqualTo("voided");
    assertThat(getSku().getVersion()).isEqualTo(2);
    assertThat(getAudits()).hasSize(auditsBefore);

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
    var auditCount = getAudits().size();

    // act
    orderPhaseProcessor.process(USER);

    // assert
    assertThat(getOrder(ORDER_ID).getStatus()).isEqualTo("voided");
    assertThat(getUnit(1).getStatus()).isEqualTo("reserved");
    assertThat(getUnit(1).getReservationId()).isEqualTo("another-order");
    assertThat(getAudits()).hasSize(auditCount);
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
    unit.setReservationId(orderId);
    unitTable.putItem(unit);
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
            factory.settingsTable(),
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
