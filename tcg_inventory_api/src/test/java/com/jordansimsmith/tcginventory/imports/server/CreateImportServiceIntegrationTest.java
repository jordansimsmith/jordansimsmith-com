package com.jordansimsmith.tcginventory.imports.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import com.jordansimsmith.tcginventory.games.client.GetGameResponse;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRequest;
import com.jordansimsmith.tcginventory.imports.client.CreateImportResponse;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRow;
import com.jordansimsmith.tcginventory.imports.client.Language;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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

@Testcontainers
class CreateImportServiceIntegrationTest {
  private static final String TABLE_NAME = "imports_create_test";
  private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<ImportItem> importTable;
  private static DynamoDbTable<ImportRowItem> importRowTable;
  private static DynamoDbTable<CreateImportOperationItem> operationTable;

  private FakeQueueClient<ImportMessage> queue;
  private FakeClock clock;
  private FakeUlidGenerator ulidGenerator;
  private ImportReadRepository reads;
  private ImportMutationRepository writes;
  private CreateImportService service;

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
    operationTable =
        enhancedClient.table(TABLE_NAME, TableSchema.fromBean(CreateImportOperationItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, importTable);
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    queue = new FakeQueueClient<>();
    clock = new FakeClock();
    clock.setTime(NOW);
    ulidGenerator = new FakeUlidGenerator();
    reads = new ImportReadRepository(importTable, importRowTable, operationTable);
    writes = new ImportMutationRepository(dynamoDbClient, TABLE_NAME);
    service =
        new CreateImportService(
            reads,
            writes,
            new FakeGamesService(),
            new ImportCsvParserFactory(new ManaBoxCsvParser()),
            queue,
            clock,
            ulidGenerator);
  }

  @Test
  void createsCsvRowsInReverseStackOrderAndDispatchesAppraisal() throws Exception {
    var response =
        service.createImport(
            new RequestContext("alice"),
            new CreateImportRequest("operation-1", "mtg", csv(), "bulk.csv", null));

    var item = reads.getImport("alice", response.importId());
    var rows = reads.findRows("alice", response.importId());
    assertThat(item.getStatus()).isEqualTo("appraising");
    assertThat(item.getRowCount()).isEqualTo(3);
    assertThat(item.getAppraisedRowCount()).isZero();
    assertThat(item.getCreatedAt()).isEqualTo(NOW);
    assertThat(item.getUpdatedAt()).isEqualTo(NOW);
    assertThat(item.getFilename()).isEqualTo("bulk.csv");
    assertThat(rows).extracting(ImportRowItem::getName).containsExactly("Sol Ring", "Opt", "Opt");
    assertThat(rows.get(1).getExternalId()).isEqualTo("opaque/id");
    assertThat(rows.get(1).getLanguage()).isEqualTo("en");
    assertThat(reads.getCreateImportOperation("alice", "operation-1").getImportId())
        .isEqualTo(response.importId());
    assertThat(queue.getSends()).hasSize(1);
    assertThat(queue.getSends().getFirst().message())
        .isEqualTo(new ImportMessage("alice", response.importId(), ImportMessageType.APPRAISE));
    assertThat(queue.getSends().getFirst().messageGroupId()).isEqualTo(response.importId());
    assertThat(queue.getSends().getFirst().messageDeduplicationId())
        .isEqualTo(response.importId() + "#appraise#0");
  }

  @Test
  void acceptsEmptyNormalizedImportAndKeepsReceiptAfterDeletion() throws Exception {
    var context = new RequestContext("alice");
    var response =
        service.createImport(
            context, new CreateImportRequest("operation-1", "mtg", null, null, List.of()));
    var item = reads.getImport("alice", response.importId());
    item.setStatus("review");
    importTable.putItem(item);
    writes.deleteImport("alice", response.importId());

    var replay =
        service.createImport(
            context,
            new CreateImportRequest(
                "operation-1", "unsupported", "replacement payload", "replacement.csv", null));

    assertThat(replay.importId()).isEqualTo(response.importId());
    assertThat(reads.getImport("alice", response.importId())).isNull();
    assertThat(queue.getSends()).hasSize(1);
  }

  @Test
  void reversesNormalizedRowsAndDoesNotInvokeCsvParser() throws Exception {
    service =
        new CreateImportService(
            reads,
            writes,
            new FakeGamesService(),
            new ImportCsvParserFactory(
                (csv, game) -> {
                  throw new AssertionError("CSV parser called for normalized rows");
                }),
            queue,
            clock,
            ulidGenerator);

    var response =
        service.createImport(
            new RequestContext("alice"),
            new CreateImportRequest(
                "operation-1",
                "mtg",
                null,
                null,
                List.of(
                    normalizedRow("opaque/one", "First"), normalizedRow("opaque/two", "Second"))));

    assertThat(reads.findRows("alice", response.importId()))
        .extracting(ImportRowItem::getName)
        .containsExactly("Second", "First");
    assertThat(queue.getSends()).hasSize(1);
  }

  @Test
  void creationReceiptsAreUserScopedAndInvalidCsvDoesNotWrite() throws Exception {
    var first =
        service.createImport(
            new RequestContext("alice"),
            new CreateImportRequest("operation-1", "mtg", null, null, List.of()));
    var second =
        service.createImport(
            new RequestContext("bob"),
            new CreateImportRequest("operation-1", "mtg", null, null, List.of()));
    assertThat(second.importId()).isNotEqualTo(first.importId());

    assertThatThrownBy(
            () ->
                service.createImport(
                    new RequestContext("alice"),
                    new CreateImportRequest("operation-2", "pokemon", "csv", null, null)))
        .isInstanceOf(BadRequestException.class);
    assertThat(queue.getSends()).hasSize(2);
    assertThat(reads.getCreateImportOperation("alice", "operation-2")).isNull();
  }

  @Test
  void writesImportsLargerThanOnePage() throws Exception {
    var rows =
        IntStream.range(0, 101)
            .mapToObj(index -> normalizedRow("opaque/" + index, "Card " + index))
            .toList();

    var response =
        service.createImport(
            new RequestContext("alice"),
            new CreateImportRequest("operation-large", "mtg", null, null, rows));

    assertThat(reads.getImport("alice", response.importId()).getRowCount()).isEqualTo(101);
    assertThat(reads.findRows("alice", response.importId())).hasSize(101);
  }

  @Test
  void rowWriteFailureLeavesNoImportOrReceipt() {
    var failingWrites = spy(writes);
    doThrow(new IllegalStateException("row write failed"))
        .when(failingWrites)
        .createRows(anyList());
    service =
        new CreateImportService(
            reads,
            failingWrites,
            new FakeGamesService(),
            new ImportCsvParserFactory(new ManaBoxCsvParser()),
            queue,
            clock,
            ulidGenerator);

    assertThatThrownBy(
            () ->
                service.createImport(
                    new RequestContext("alice"),
                    new CreateImportRequest(
                        "operation-failure",
                        "mtg",
                        null,
                        null,
                        List.of(normalizedRow("id", "Opt")))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("row write failed");

    assertThat(reads.getImport("alice", "FAKE_ULID_0000000001")).isNull();
    assertThat(reads.getCreateImportOperation("alice", "operation-failure")).isNull();
    assertThat(queue.getSends()).isEmpty();
  }

  @Test
  void queueFailureLeavesCommittedImportAndReplayDoesNotResend() throws Exception {
    @SuppressWarnings("unchecked")
    QueueClient<ImportMessage> failingQueue = (QueueClient<ImportMessage>) mock(QueueClient.class);
    doThrow(new IllegalStateException("queue unavailable"))
        .when(failingQueue)
        .send(any(), anyString(), anyString());
    service =
        new CreateImportService(
            reads,
            writes,
            new FakeGamesService(),
            new ImportCsvParserFactory(new ManaBoxCsvParser()),
            failingQueue,
            clock,
            ulidGenerator);
    var request = new CreateImportRequest("operation-queue", "mtg", null, null, List.of());

    assertThatThrownBy(() -> service.createImport(new RequestContext("alice"), request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("queue unavailable");

    var receipt = reads.getCreateImportOperation("alice", "operation-queue");
    assertThat(receipt).isNotNull();
    assertThat(reads.getImport("alice", receipt.getImportId())).isNotNull();
    var replay =
        service.createImport(
            new RequestContext("alice"),
            new CreateImportRequest("operation-queue", "unsupported", "bad csv", null, null));
    assertThat(replay.importId()).isEqualTo(receipt.getImportId());
    verify(failingQueue).send(any(), anyString(), anyString());
  }

  @Test
  void createImportAtomicallyRejectsExistingParentOrReceipt() {
    var existingParent = ImportItem.create("alice", "mtg", "parent-exists", null, 0, NOW);
    importTable.putItem(existingParent);
    assertThatThrownBy(
            () ->
                writes.createImport(
                    ImportItem.create("alice", "mtg", "parent-exists", null, 1, NOW),
                    CreateImportOperationItem.create("alice", "new-operation", "parent-exists")))
        .isInstanceOf(RuntimeException.class);
    assertThat(reads.getCreateImportOperation("alice", "new-operation")).isNull();
    assertThat(reads.getImport("alice", "parent-exists").getRowCount()).isZero();

    operationTable.putItem(
        CreateImportOperationItem.create("alice", "receipt-exists", "original-import"));
    assertThatThrownBy(
            () ->
                writes.createImport(
                    ImportItem.create("alice", "mtg", "new-parent", null, 0, NOW),
                    CreateImportOperationItem.create("alice", "receipt-exists", "new-parent")))
        .isInstanceOf(RuntimeException.class);
    assertThat(reads.getImport("alice", "new-parent")).isNull();
    assertThat(reads.getCreateImportOperation("alice", "receipt-exists").getImportId())
        .isEqualTo("original-import");
  }

  @Test
  void competingAdmissionsCommitOnlyOneReceiptAndParent()
      throws ExecutionException, InterruptedException, ServiceException {
    var bothReadReceipt = new CountDownLatch(2);
    var games = spy(new FakeGamesService());
    doAnswer(
            invocation -> {
              bothReadReceipt.countDown();
              if (!bothReadReceipt.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("competing create did not reach Games lookup");
              }
              return new GetGameResponse(FakeGamesService.MAGIC_THE_GATHERING);
            })
        .when(games)
        .getGame(any(), any(GetGameRequest.class));
    var competingService =
        new CreateImportService(
            reads,
            writes,
            games,
            new ImportCsvParserFactory(new ManaBoxCsvParser()),
            queue,
            clock,
            ulidGenerator);
    var request =
        new CreateImportRequest(
            "same-operation", "mtg", null, null, List.of(normalizedRow("id", "Opt")));
    var executor = Executors.newFixedThreadPool(2);

    try {
      var first =
          executor.submit(() -> attempt(competingService, new RequestContext("alice"), request));
      var second =
          executor.submit(() -> attempt(competingService, new RequestContext("alice"), request));
      var outcomes = List.of(first.get(), second.get());
      var winners = outcomes.stream().filter(outcome -> outcome.response() != null).toList();

      assertThat(winners).hasSize(1);
      assertThat(outcomes.stream().filter(outcome -> outcome.failure() != null).toList())
          .hasSize(1);
      var importId = winners.getFirst().response().importId();
      assertThat(reads.getCreateImportOperation("alice", "same-operation").getImportId())
          .isEqualTo(importId);
      assertThat(reads.getImport("alice", importId)).isNotNull();
      assertThat(queue.getSends()).hasSize(1);
    } finally {
      executor.shutdownNow();
    }
  }

  private static String csv() {
    return "Name,Set code,Set name,Collector number,Foil,Quantity,Scryfall ID,Condition,Language"
        + "\nOpt,DOM,Dominaria,60,Normal,2,opaque/id,near_mint,en"
        + "\nSol Ring,CMR,Commander Legends,472,Foil,1,opaque/sol-ring,excellent,ja";
  }

  private static CreateImportRow normalizedRow(String externalId, String name) {
    return new CreateImportRow(
        externalId, name, "dom", "Dominaria", "60", "normal", Condition.NM, Language.ENGLISH);
  }

  private static CreateAttempt attempt(
      CreateImportService service, RequestContext context, CreateImportRequest request) {
    try {
      return new CreateAttempt(service.createImport(context, request), null);
    } catch (Throwable error) {
      return new CreateAttempt(null, error);
    }
  }

  private record CreateAttempt(CreateImportResponse response, Throwable failure) {}
}
