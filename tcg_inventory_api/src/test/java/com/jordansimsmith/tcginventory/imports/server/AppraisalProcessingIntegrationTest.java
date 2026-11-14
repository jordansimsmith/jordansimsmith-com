package com.jordansimsmith.tcginventory.imports.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.pricing.client.FakePricingService;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceResponse;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Testcontainers
class AppraisalProcessingIntegrationTest {
  private static final String TABLE_NAME = "imports_appraisal_test";
  private static final String USER = "appraisal-user";
  private static final String IMPORT_ID = "opaque/import-id";
  private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<ImportItem> importTable;
  private static DynamoDbTable<ImportRowItem> importRowTable;
  private static DynamoDbTable<CreateImportOperationItem> createImportOperationTable;

  private ImportReadRepository importReadRepository;
  private ImportMutationRepository importMutationRepository;
  private FakeQueueClient<ImportMessage> queue;
  private FakeClock clock;
  private FakePricingService pricingService;
  private AppraisalPolicy appraisalPolicy;

  @BeforeAll
  static void createTable() {
    dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    importTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(ImportItem.class));
    importRowTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(ImportRowItem.class));
    createImportOperationTable =
        enhancedClient.table(TABLE_NAME, TableSchema.fromBean(CreateImportOperationItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, importTable);
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    importReadRepository =
        new ImportReadRepository(importTable, importRowTable, createImportOperationTable);
    importMutationRepository = new ImportMutationRepository(dynamoDbClient, TABLE_NAME);
    queue = new FakeQueueClient<>();
    clock = new FakeClock();
    clock.setTime(NOW);
    pricingService = new FakePricingService();
    pricingService.seedPrice(
        USER,
        "opaque/card",
        Condition.NM,
        new GetPriceResponse(new BigDecimal("1.00"), new BigDecimal("1.25")));
    appraisalPolicy = new AppraisalPolicy();
  }

  @Test
  void appraisalShouldCheckpointOneHundredRowsAndResumeUntilComplete() throws Exception {
    var rows = rows(101);
    rows.getFirst().setDecision("discard");
    importTable.putItem(parent(101, 0));
    rows.forEach(importRowTable::putItem);
    var resolvedPositions = new ArrayList<Integer>();
    var resolver =
        (AppraisalResolver)
            (ctx, row) -> {
              resolvedPositions.add(row.getPosition());
              return keepResult();
            };
    var handler = handler(resolver);
    var firstMessage = message();

    handler.handleMessage(new RequestContext(USER), firstMessage);

    assertThat(importReadRepository.getImport(USER, IMPORT_ID).getAppraisedRowCount())
        .isEqualTo(100);
    assertThat(importReadRepository.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("appraising");
    assertThat(resolvedPositions).hasSize(99).doesNotContain(1).contains(100);
    assertThat(importReadRepository.getRow(USER, IMPORT_ID, 100).getDecision()).isEqualTo("keep");
    assertThat(importReadRepository.getRow(USER, IMPORT_ID, 100).getMarketPrice())
        .isEqualTo("1.00");
    assertThat(queue.getSends())
        .containsExactly(
            new FakeQueueClient.SentMessage<>(
                firstMessage, IMPORT_ID, IMPORT_ID + "#appraise#100"));

    handler.handleMessage(new RequestContext(USER), queue.getMessages().getFirst());

    var completed = importReadRepository.getImport(USER, IMPORT_ID);
    assertThat(completed.getAppraisedRowCount()).isEqualTo(101);
    assertThat(completed.getStatus()).isEqualTo("review");
    assertThat(completed.getUpdatedAt()).isEqualTo(NOW);
    assertThat(resolvedPositions).hasSize(100).contains(101);
    assertThat(queue.getSends()).hasSize(1);
  }

  @Test
  void appraisalShouldRetryPartialBatchWithoutRepeatingSavedRows() {
    importTable.putItem(parent(3, 0));
    rows(3).forEach(importRowTable::putItem);
    var attempts = new ArrayList<Integer>();
    var failOnce = new AtomicBoolean(true);
    var resolver =
        (AppraisalResolver)
            (ctx, row) -> {
              attempts.add(row.getPosition());
              if (row.getPosition() == 3 && failOnce.getAndSet(false)) {
                throw new ServiceUnavailableException("pricing lookup failed");
              }
              return keepResult();
            };
    var handler = handler(resolver);

    assertThatThrownBy(() -> handler.handleMessage(new RequestContext(USER), message()))
        .isInstanceOf(ServiceUnavailableException.class);

    assertThat(importReadRepository.getImport(USER, IMPORT_ID).getAppraisedRowCount()).isEqualTo(0);
    assertThat(importReadRepository.getRow(USER, IMPORT_ID, 1).getDecision()).isEqualTo("keep");
    assertThat(importReadRepository.getRow(USER, IMPORT_ID, 2).getDecision()).isEqualTo("keep");

    assertThatCode(() -> handler.handleMessage(new RequestContext(USER), message()))
        .doesNotThrowAnyException();

    assertThat(attempts).containsExactly(1, 2, 3, 3);
    assertThat(importReadRepository.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("review");
    assertThat(importReadRepository.getImport(USER, IMPORT_ID).getAppraisedRowCount()).isEqualTo(3);
  }

  @Test
  void emptyImportShouldFinishAtZeroWithoutSendingAContinuation() throws Exception {
    importTable.putItem(parent(0, 0));

    handler((ctx, row) -> keepResult()).handleMessage(new RequestContext(USER), message());

    assertThat(importReadRepository.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("review");
    assertThat(importReadRepository.getImport(USER, IMPORT_ID).getAppraisedRowCount()).isZero();
    assertThat(queue.getSends()).isEmpty();
  }

  @Test
  void continuationSendFailureShouldLeaveCheckpointForRedelivery() throws Exception {
    importTable.putItem(parent(101, 0));
    rows(101).forEach(importRowTable::putItem);
    var resolver = (AppraisalResolver) (ctx, row) -> keepResult();
    QueueClient<ImportMessage> failingQueue = mock(QueueClient.class);
    doThrow(new IllegalStateException("queue send failed"))
        .when(failingQueue)
        .send(any(), any(), any());

    assertThatThrownBy(
            () ->
                handler(resolver, failingQueue).handleMessage(new RequestContext(USER), message()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("queue send failed");

    assertThat(importReadRepository.getImport(USER, IMPORT_ID).getAppraisedRowCount())
        .isEqualTo(100);
    assertThat(importReadRepository.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("appraising");

    handler(resolver, queue).handleMessage(new RequestContext(USER), message());

    assertThat(importReadRepository.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("review");
    assertThat(importReadRepository.getImport(USER, IMPORT_ID).getAppraisedRowCount())
        .isEqualTo(101);
  }

  @Test
  void appraisalWritesShouldPreserveOtherAttributesAndOmitNullOptionalResults() {
    var row = new ImportRowItem();
    row.setPk(ImportRowItem.formatPk(USER, IMPORT_ID));
    row.setSk(ImportRowItem.formatSk(1));
    row.setPosition(1);
    row.setName("Lightning Bolt");
    row.setMarketPrice("0.30");
    importRowTable.putItem(row);

    importMutationRepository.recordAppraisal(
        USER, IMPORT_ID, 1, "review", "unresolvable", null, null, null, null);

    var stored =
        dynamoDbClient
            .getItem(
                request ->
                    request
                        .tableName(TABLE_NAME)
                        .key(
                            Map.of(
                                ImportRowItem.PK,
                                AttributeValue.fromS(ImportRowItem.formatPk(USER, IMPORT_ID)),
                                ImportRowItem.SK,
                                AttributeValue.fromS(ImportRowItem.formatSk(1)))))
            .item();
    assertThat(stored)
        .containsEntry(ImportRowItem.DECISION, AttributeValue.fromS("review"))
        .containsEntry(ImportRowItem.DECISION_REASON, AttributeValue.fromS("unresolvable"))
        .containsEntry(ImportRowItem.NAME, AttributeValue.fromS("Lightning Bolt"))
        .containsEntry(ImportRowItem.MARKET_PRICE, AttributeValue.fromS("0.30"))
        .doesNotContainKeys(ImportRowItem.SUGGESTED_PRICE, ImportRowItem.FETCHTCG_CARD_ID);
  }

  @Test
  void appraisalHandlerShouldApplyThePriceThresholdToResolvedCards() throws Exception {
    importTable.putItem(parent(1, 0));
    rows(1).forEach(importRowTable::putItem);
    pricingService.seedPrice(
        USER,
        "below-threshold/card",
        Condition.NM,
        new GetPriceResponse(new BigDecimal("0.24"), new BigDecimal("1.25")));

    handler(
            (ctx, row) ->
                new AppraisalResult(
                    AppraisalResult.Decision.RESOLVED, null, "below-threshold/card", 42))
        .handleMessage(new RequestContext(USER), message());

    var stored = importReadRepository.getRow(USER, IMPORT_ID, 1);
    assertThat(stored.getDecision()).isEqualTo("discard");
    assertThat(stored.getDecisionReason()).isEqualTo("below threshold");
    assertThat(stored.getMarketPrice()).isEqualTo("0.24");
    assertThat(stored.getSuggestedPrice()).isNull();
    assertThat(stored.getFetchtcgCardId()).isNull();
    assertThat(stored.getFetchtcgSetId()).isNull();
  }

  @Test
  void repeatedRowsShouldResolveAndFetchPricingIndependently() throws Exception {
    // arrange
    var rows = rows(2);
    rows.get(1).setExternalId(rows.getFirst().getExternalId());
    importTable.putItem(parent(2, 0));
    rows.forEach(importRowTable::putItem);
    var resolvedPositions = new ArrayList<Integer>();
    PricingService pricingSpy = spy(pricingService);
    var resolver =
        (AppraisalResolver)
            (ctx, row) -> {
              resolvedPositions.add(row.getPosition());
              return keepResult();
            };

    // act
    handler(resolver, queue, pricingSpy).handleMessage(new RequestContext(USER), message());

    // assert
    assertThat(resolvedPositions).containsExactly(1, 2);
    verify(pricingSpy, times(2)).getPrice(any(), any());
    assertThat(importReadRepository.getRow(USER, IMPORT_ID, 1).getDecision()).isEqualTo("keep");
    assertThat(importReadRepository.getRow(USER, IMPORT_ID, 2).getDecision()).isEqualTo("keep");
  }

  private AppraisalMessageHandler handler(AppraisalResolver resolver) {
    return handler(resolver, queue, pricingService);
  }

  private AppraisalMessageHandler handler(
      AppraisalResolver resolver, QueueClient<ImportMessage> importsQueue) {
    return handler(resolver, importsQueue, pricingService);
  }

  private AppraisalMessageHandler handler(
      AppraisalResolver resolver,
      QueueClient<ImportMessage> importsQueue,
      PricingService priceService) {
    return new AppraisalMessageHandler(
        importReadRepository,
        importMutationRepository,
        new AppraisalResolverFactory(resolver),
        priceService,
        appraisalPolicy,
        importsQueue,
        clock);
  }

  private static ImportItem parent(int rowCount, int appraisedRowCount) {
    var item = new ImportItem();
    item.setPk(ImportItem.formatPk(USER));
    item.setSk(ImportItem.formatSk(IMPORT_ID));
    item.setImportId(IMPORT_ID);
    item.setGame("mtg");
    item.setStatus("appraising");
    item.setRowCount(rowCount);
    item.setAppraisedRowCount(appraisedRowCount);
    item.setCreatedAt(NOW);
    return item;
  }

  private static List<ImportRowItem> rows(int count) {
    var rows = new ArrayList<ImportRowItem>();
    for (int position = 1; position <= count; position++) {
      var row = new ImportRowItem();
      row.setPk(ImportRowItem.formatPk(USER, IMPORT_ID));
      row.setSk(ImportRowItem.formatSk(position));
      row.setPosition(position);
      row.setName("Card " + position);
      row.setSetCode("set");
      row.setCondition("NM");
      row.setFinish("normal");
      row.setExternalId("opaque-id-" + position);
      rows.add(row);
    }
    return rows;
  }

  private static AppraisalResult keepResult() {
    return new AppraisalResult(AppraisalResult.Decision.RESOLVED, null, "opaque/card", 42);
  }

  private static ImportMessage message() {
    return new ImportMessage(USER, IMPORT_ID, ImportMessageType.APPRAISE);
  }
}
