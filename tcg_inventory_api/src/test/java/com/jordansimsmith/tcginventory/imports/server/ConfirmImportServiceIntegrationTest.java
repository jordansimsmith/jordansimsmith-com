package com.jordansimsmith.tcginventory.imports.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.jordansimsmith.tcginventory.imports.client.ConfirmImportRequest;
import com.jordansimsmith.tcginventory.imports.client.GetImportRequest;
import com.jordansimsmith.tcginventory.inventory.client.FakeInventoryService;
import com.jordansimsmith.time.FakeClock;
import java.time.Instant;
import java.util.Map;
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
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@Testcontainers
class ConfirmImportServiceIntegrationTest {
  private static final String TABLE_NAME = "tcg_inventory";
  private static final String USER = "confirm-service-user";
  private static final String IMPORT_ID = "opaque/import:id";
  private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<ImportItem> importTable;
  private static DynamoDbTable<ImportRowItem> importRowTable;
  private static DynamoDbTable<CreateImportOperationItem> createImportOperationTable;
  private static ImportReadRepository reads;
  private FakeClock clock;
  private ImportMutationRepository writes;
  private FakeQueueClient<ImportMessage> queue;
  private ConfirmImportService service;
  private FakeInventoryService inventory;
  private ImportConfirmationMessageHandler confirmationHandler;

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
    reads = new ImportReadRepository(importTable, importRowTable, createImportOperationTable);
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    clock = new FakeClock();
    clock.setTime(NOW);
    writes = new ImportMutationRepository(dynamoDbClient, TABLE_NAME);
    queue = new FakeQueueClient<>();
    service = new ConfirmImportService(reads, writes, queue, clock);
    inventory = new FakeInventoryService(new FakeGamesService());
    confirmationHandler = new ImportConfirmationMessageHandler(reads, writes, inventory, clock);
  }

  @Test
  void admissionDispatchesOnceAndWorkerCompletionProvidesPlacement() throws Exception {
    var parent = ImportItem.create(USER, "mtg", IMPORT_ID, null, 3, NOW);
    parent.setStatus("review");
    importTable.putItem(parent);
    importRowTable.putItem(row(1, "first-card"));
    importRowTable.putItem(row(3, "second-card"));
    var ctx = new RequestContext(USER);

    service.confirmImport(ctx, new ConfirmImportRequest(IMPORT_ID));
    service.confirmImport(ctx, new ConfirmImportRequest(IMPORT_ID));

    assertThat(queue.getSends())
        .containsExactly(
            new FakeQueueClient.SentMessage<>(
                new ImportMessage(USER, IMPORT_ID, ImportMessageType.CONFIRM),
                IMPORT_ID,
                IMPORT_ID + "#confirm"));
    assertThat(reads.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("confirming");
    assertThat(reads.getImport(USER, IMPORT_ID).getUpdatedAt()).isEqualTo(NOW);

    confirmationHandler.handleMessage(ctx, queue.getMessages().getFirst());

    assertThat(reads.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("confirmed");
    assertThat(reads.getRow(USER, IMPORT_ID, 1).getSkuId()).isNotNull();
    assertThat(reads.getRow(USER, IMPORT_ID, 3).getSkuId()).isNotNull();
    var detail = new GetImportService(reads).getImport(ctx, new GetImportRequest(IMPORT_ID));
    assertThat(detail.unitCount()).isEqualTo(2);
    assertThat(detail.placementInstructions()).hasSize(1);
  }

  @Test
  void conditionalAdmissionRequiresReviewAndPreservesOtherParentFields() {
    var parent = ImportItem.create(USER, "mtg", IMPORT_ID, "filename.csv", 8, NOW);
    parent.setStatus("review");
    parent.setAppraisedRowCount(8);
    importTable.putItem(parent);

    writes.startConfirmation(USER, IMPORT_ID, NOW.plusSeconds(5));

    var updated = reads.getImport(USER, IMPORT_ID);
    assertThat(updated.getStatus()).isEqualTo("confirming");
    assertThat(updated.getUpdatedAt()).isEqualTo(NOW.plusSeconds(5));
    assertThat(updated.getCreatedAt()).isEqualTo(NOW);
    assertThat(updated.getFilename()).isEqualTo("filename.csv");
    assertThat(updated.getAppraisedRowCount()).isEqualTo(8);
    assertThatThrownBy(() -> writes.startConfirmation(USER, IMPORT_ID, NOW.plusSeconds(6)))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThatThrownBy(() -> writes.startConfirmation(USER, "missing", NOW))
        .isInstanceOf(ConditionalCheckFailedException.class);
  }

  @Test
  void admissionDoesNotRejectAnUnrelatedActiveJob() throws ServiceException {
    var parent = ImportItem.create(USER, "mtg", IMPORT_ID, null, 0, NOW);
    parent.setStatus("review");
    importTable.putItem(parent);
    dynamoDbClient.putItem(
        PutItemRequest.builder()
            .tableName(TABLE_NAME)
            .item(
                Map.of(
                    "pk", AttributeValue.fromS("USER#" + USER),
                    "sk", AttributeValue.fromS("JOB#publish-job"),
                    "status", AttributeValue.fromS("queued")))
            .build());

    service.confirmImport(new RequestContext(USER), new ConfirmImportRequest(IMPORT_ID));

    assertThat(reads.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("confirming");
    assertThat(queue.getMessages())
        .containsExactly(new ImportMessage(USER, IMPORT_ID, ImportMessageType.CONFIRM));
  }

  @Test
  void sendFailureLeavesAdmissionCommittedAndReplayDoesNotResend() throws ServiceException {
    var parent = ImportItem.create(USER, "mtg", IMPORT_ID, null, 0, NOW);
    parent.setStatus("review");
    importTable.putItem(parent);
    @SuppressWarnings("unchecked")
    var failingQueue = (QueueClient<ImportMessage>) mock(QueueClient.class);
    doThrow(new IllegalStateException("queue unavailable"))
        .when(failingQueue)
        .send(any(), any(), any());
    var failingService = new ConfirmImportService(reads, writes, failingQueue, clock);
    var ctx = new RequestContext(USER);

    assertThatThrownBy(() -> failingService.confirmImport(ctx, new ConfirmImportRequest(IMPORT_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("queue unavailable");
    assertThat(reads.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("confirming");

    service.confirmImport(ctx, new ConfirmImportRequest(IMPORT_ID));

    assertThat(queue.getMessages()).isEmpty();
  }

  @Test
  void admissionUsesTheAuthenticatedUserPartition() {
    var otherUserImport = ImportItem.create("other-user", "mtg", IMPORT_ID, null, 0, NOW);
    otherUserImport.setStatus("review");
    importTable.putItem(otherUserImport);

    assertThatThrownBy(
            () ->
                service.confirmImport(
                    new RequestContext(USER), new ConfirmImportRequest(IMPORT_ID)))
        .isInstanceOf(NotFoundException.class);
    assertThat(queue.getMessages()).isEmpty();
    assertThat(reads.getImport("other-user", IMPORT_ID).getStatus()).isEqualTo("review");
  }

  @Test
  void photoGateReadsRowsAcrossDynamoDbPages() {
    var parent = ImportItem.create(USER, "mtg", IMPORT_ID, null, 11, NOW);
    parent.setStatus("review");
    importTable.putItem(parent);
    var largeName = "x".repeat(100_000);
    for (int position = 1; position <= 11; position++) {
      var row =
          ImportRowItem.create(
              USER,
              IMPORT_ID,
              position,
              largeName,
              "set",
              "Set",
              Integer.toString(position),
              "normal",
              "NM",
              "external-" + position,
              "en");
      row.setDecision("keep");
      row.setSuggestedPrice(position == 11 ? "20.00" : "1.00");
      importRowTable.putItem(row);
    }

    assertThatThrownBy(
            () -> service.confirmImport(new RequestContext(USER), new ConfirmImportRequest(IMPORT_ID)))
        .isInstanceOf(ConflictException.class)
        .hasMessage("1 rows need photos before confirm");
    assertThat(queue.getMessages()).isEmpty();
    assertThat(reads.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("review");
  }

  private static ImportRowItem row(int position, String externalId) {
    var row =
        ImportRowItem.create(
            USER,
            IMPORT_ID,
            position,
            "Card " + externalId,
            "set",
            "Set",
            Integer.toString(position),
            "normal",
            "NM",
            externalId,
            "en");
    row.setDecision("keep");
    row.setSuggestedPrice("1.00");
    row.setFetchtcgSetId(42);
    return row;
  }
}
