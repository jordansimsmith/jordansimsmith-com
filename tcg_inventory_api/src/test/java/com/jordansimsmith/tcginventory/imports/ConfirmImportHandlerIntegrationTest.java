package com.jordansimsmith.tcginventory.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.AuditItem;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.CreateUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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

@Testcontainers
public class ConfirmImportHandlerIntegrationTest {

  private FakeClock fakeClock;
  private FakeUlidGenerator fakeUlidGenerator;
  private TcgInventoryTestFactory factory;
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImportItem> importTable;
  private DynamoDbTable<ImportRowItem> importRowTable;
  private DynamoDbTable<AuditItem> auditTable;
  private DynamoDbTable<SkuItem> skuTable;
  private DynamoDbTable<UnitItem> unitTable;
  private DynamoDbTable<JobItem> jobTable;

  private ConfirmImportHandler confirmImportHandler;
  private GetImportHandler getImportHandler;
  private ImportConfirmationJobProcessor importConfirmationJobProcessor;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    var table = factory.tableDefinition();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);

    fakeClock = factory.fakeClock();
    fakeUlidGenerator = factory.fakeUlidGenerator();
    objectMapper = factory.objectMapper();
    importTable = factory.importTable();
    importRowTable = factory.importRowTable();
    auditTable = factory.auditTable();
    skuTable = factory.skuTable();
    unitTable = factory.unitTable();
    jobTable = factory.jobTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeUlidGenerator.reset();

    confirmImportHandler = new ConfirmImportHandler(factory);
    getImportHandler = new GetImportHandler(factory);
    importConfirmationJobProcessor =
        createProcessor(new ImportsRepository(factory.dynamoDbClient(), jobTable));
  }

  @Test
  void confirmShouldAllocateSequenceNumbersAndCreateUnits() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportInReview("jordan", "import1", 3);
    createKeepRow("jordan", "import1", 1, "scryfall-1", "normal", "NM", "Card A");
    createKeepRow("jordan", "import1", 2, "scryfall-1", "normal", "NM", "Card B");
    createKeepRow("jordan", "import1", 3, "scryfall-2", "foil", "LP", "Card C");

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    assertThat(response.getBody()).isNull();
    assertThat(getImportItem("import1").getStatus()).isEqualTo("confirming");
    var confirmingDetail =
        objectMapper.readTree(
            getImportHandler
                .handleRequest(buildEvent("jordan", Map.of("import_id", "import1")), null)
                .getBody());
    assertThat(confirmingDetail.get("unit_count").isNull()).isTrue();
    assertThat(confirmingDetail.get("placement_instructions").isNull()).isTrue();
    processConfirmation("jordan", "import1");
    processConfirmation("jordan", "import1");
    var detailResponse =
        getImportHandler.handleRequest(buildEvent("jordan", Map.of("import_id", "import1")), null);
    var body = objectMapper.readTree(detailResponse.getBody());
    assertThat(body.get("status").asText()).isEqualTo("confirmed");
    assertThat(body.get("unit_count").asInt()).isEqualTo(3);
    assertThat(body.get("total_suggested_price").asText()).isEqualTo("4.50");

    var sku1Pk = SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    var unit0 =
        unitTable.getItem(
            Key.builder().partitionValue(sku1Pk).sortValue(UnitItem.formatSk(0)).build());
    assertThat(unit0).isNotNull();
    assertThat(unit0.getStatus()).isEqualTo("in_stock");
    assertThat(unit0.getImportId()).isNull();
    assertThat(unit0.getGame()).isEqualTo("mtg");
    assertThat(unit0.getGsi3pk()).isEqualTo("USER#jordan#UNITS#mtg");

    var unit1 =
        unitTable.getItem(
            Key.builder().partitionValue(sku1Pk).sortValue(UnitItem.formatSk(1)).build());
    assertThat(unit1).isNotNull();

    var sku1 =
        skuTable.getItem(
            Key.builder().partitionValue(sku1Pk).sortValue(SkuItem.formatSk()).build());
    assertThat(countUnits(sku1Pk)).isEqualTo(2);
    assertThat(sku1.getVersion()).isEqualTo(2);
    assertThat(sku1.getDirty()).isTrue();
    assertThat(sku1.getGsi1pk()).isEqualTo(SkuItem.formatGsi1pk("jordan"));
    assertThat(sku1.getSkuId()).isEqualTo("mtg#scryfall#scryfall-1#normal#NM");
    assertThat(sku1.getGame()).isEqualTo("mtg");
    assertThat(sku1.getExternalId()).isEqualTo("scryfall-1");

    assertThat(getImportRow("import1", 1).getSkuId())
        .isEqualTo("mtg#scryfall#scryfall-1#normal#NM");
    assertThat(getImportRow("import1", 1).getSequenceNumber()).isZero();
    assertThat(getImportRow("import1", 1).getLocation()).isEqualTo("A0-0");
    assertThat(getImportRow("import1", 2).getSequenceNumber()).isEqualTo(1);
    assertThat(getImportRow("import1", 2).getLocation()).isEqualTo("A0-1");
    assertThat(getImportRow("import1", 3).getSkuId()).isEqualTo("mtg#scryfall#scryfall-2#foil#LP");
    assertThat(getImportRow("import1", 3).getSequenceNumber()).isEqualTo(2);
    assertThat(getImportRow("import1", 3).getLocation()).isEqualTo("A0-2");

    var sku2Pk = SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-2#foil#LP");
    var sku2 =
        skuTable.getItem(
            Key.builder().partitionValue(sku2Pk).sortValue(SkuItem.formatSk()).build());
    assertThat(countUnits(sku2Pk)).isEqualTo(1);
    assertThat(sku2.getVersion()).isEqualTo(1);
    assertThat(sku2.getDirty()).isTrue();
    var auditEntries = getAuditEntries("jordan");
    assertThat(auditEntries).hasSize(3);
    assertThat(auditEntries)
        .allSatisfy(
            audit -> {
              assertThat(audit.getEventType()).isEqualTo("create");
              assertThat(audit.getImportId()).isNull();
              assertThat(audit.getSequenceNumber()).isNotNull();
              assertThat(audit.getBeforeStatus()).isEqualTo("absent");
              assertThat(audit.getAfterStatus()).isEqualTo("in_stock");
            });
  }

  @Test
  void confirmShouldReturn409WhenAnotherJobIsActive() throws Exception {
    // arrange
    createImportInReview("jordan", "import1", 1);
    createKeepRow("jordan", "import1", 1, "scryfall-1", "normal", "NM", "Card A");
    jobTable.putItem(
        JobItem.create(
            "jordan", "publish-job", "publish", null, null, Instant.ofEpochSecond(1700000000)));

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("another job is in progress");
    assertThat(getImportItem("import1").getStatus()).isEqualTo("review");
    var jobs = findJobs("jordan");
    assertThat(jobs).anyMatch(job -> "publish-job".equals(job.getJobId()));
    assertThat(jobs)
        .noneMatch(
            job ->
                "import_confirmation".equals(job.getJobType())
                    && "import1".equals(job.getImportId()));
  }

  @Test
  void confirmShouldReturn409WhenNotInReview() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var importItem =
        ImportItem.create(
            "jordan", "mtg", "import1", null, 1, null, Instant.ofEpochSecond(1700000000));
    importTable.putItem(importItem);

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
  }

  @Test
  void confirmShouldReturn204WhenAlreadyConfirmed() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var importItem =
        ImportItem.create(
            "jordan", "mtg", "import1", null, 1, null, Instant.ofEpochSecond(1700000000));
    importItem.setStatus("confirmed");
    importTable.putItem(importItem);
    jobTable.putItem(
        JobItem.create(
            "jordan", "publish-job", "publish", null, null, Instant.ofEpochSecond(1700000000)));

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);
  }

  @Test
  void confirmShouldReturn202WithoutCreatingAnotherJobWhenAlreadyConfirming() {
    // arrange
    var importItem =
        ImportItem.create(
            "jordan", "mtg", "import1", null, 1, null, Instant.ofEpochSecond(1700000000));
    importItem.setStatus("confirming");
    importTable.putItem(importItem);
    jobTable.putItem(
        JobItem.create(
            "jordan", "publish-job", "publish", null, null, Instant.ofEpochSecond(1700000000)));

    var jobCount = jobTable.scan().items().stream().count();

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    assertThat(response.getBody()).isNull();
    assertThat(jobTable.scan().items().stream().count()).isEqualTo(jobCount);
  }

  @Test
  void confirmShouldReturnPlacementInstructions() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportInReview("jordan", "import1", 2);
    createKeepRow("jordan", "import1", 1, "scryfall-1", "normal", "NM", "Sol Ring");
    createKeepRow("jordan", "import1", 2, "scryfall-2", "normal", "NM", "Lightning Bolt");

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    processConfirmation("jordan", "import1");
    var detailResponse =
        getImportHandler.handleRequest(buildEvent("jordan", Map.of("import_id", "import1")), null);
    var body = objectMapper.readTree(detailResponse.getBody());
    assertThat(body.get("total_suggested_price").asText()).isEqualTo("3.00");
    var instructions = body.get("placement_instructions");
    assertThat(instructions).hasSize(1);
    assertThat(instructions.get(0).get("block").asText()).isEqualTo("A0");
    assertThat(instructions.get(0).get("from_location").asText()).isEqualTo("A0-0");
    assertThat(instructions.get(0).get("to_location").asText()).isEqualTo("A0-1");
    assertThat(instructions.get(0).get("from_name").asText()).isEqualTo("Sol Ring");
    assertThat(instructions.get(0).get("to_name").asText()).isEqualTo("Lightning Bolt");
    assertThat(instructions.get(0).get("unit_count").asInt()).isEqualTo(2);
  }

  @Test
  void getConfirmedImportShouldSplitPlacementInstructionsAtGapsAndBlockBoundaries()
      throws Exception {
    // arrange
    var importItem =
        ImportItem.create(
            "jordan", "mtg", "import1", "test.csv", 5, null, Instant.ofEpochSecond(1700000000));
    importItem.setStatus("confirmed");
    importTable.putItem(importItem);

    var sequenceNumbers = List.of(4200, 4201, 4204, 4299, 4300);
    var names = List.of("Card A", "Card B", "Card C", "Card D", "Card E");
    for (int index = 0; index < sequenceNumbers.size(); index++) {
      int position = index + 1;
      createKeepRow(
          "jordan", "import1", position, "card-" + position, "normal", "NM", names.get(index));
      var row =
          importRowTable.getItem(
              Key.builder()
                  .partitionValue(ImportRowItem.formatPk("jordan", "import1"))
                  .sortValue(ImportRowItem.formatSk(position))
                  .build());
      row.setSequenceNumber(sequenceNumbers.get(index));
      row.setSkuId("mtg#scryfall#card-" + position + "#normal#NM");
      row.setLocation(List.of("A42-0", "A42-1", "A42-4", "A42-99", "A43-0").get(index));
      importRowTable.putItem(row);
    }

    // act
    var response =
        getImportHandler.handleRequest(buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("unit_count").asInt()).isEqualTo(5);
    assertThat(body.get("total_suggested_price").asText()).isEqualTo("7.50");
    var instructions = body.get("placement_instructions");
    assertThat(instructions).hasSize(4);
    assertThat(instructions.get(0).get("block").asText()).isEqualTo("A42");
    assertThat(instructions.get(0).get("from_location").asText()).isEqualTo("A42-0");
    assertThat(instructions.get(0).get("to_location").asText()).isEqualTo("A42-1");
    assertThat(instructions.get(0).get("from_name").asText()).isEqualTo("Card A");
    assertThat(instructions.get(0).get("to_name").asText()).isEqualTo("Card B");
    assertThat(instructions.get(0).get("unit_count").asInt()).isEqualTo(2);
    assertThat(instructions.get(1).get("block").asText()).isEqualTo("A42");
    assertThat(instructions.get(1).get("from_location").asText()).isEqualTo("A42-4");
    assertThat(instructions.get(1).get("to_location").asText()).isEqualTo("A42-4");
    assertThat(instructions.get(1).get("from_name").asText()).isEqualTo("Card C");
    assertThat(instructions.get(1).get("to_name").asText()).isEqualTo("Card C");
    assertThat(instructions.get(1).get("unit_count").asInt()).isEqualTo(1);
    assertThat(instructions.get(2).get("block").asText()).isEqualTo("A42");
    assertThat(instructions.get(2).get("from_location").asText()).isEqualTo("A42-99");
    assertThat(instructions.get(2).get("to_location").asText()).isEqualTo("A42-99");
    assertThat(instructions.get(2).get("from_name").asText()).isEqualTo("Card D");
    assertThat(instructions.get(2).get("to_name").asText()).isEqualTo("Card D");
    assertThat(instructions.get(2).get("unit_count").asInt()).isEqualTo(1);
    assertThat(instructions.get(3).get("block").asText()).isEqualTo("A43");
    assertThat(instructions.get(3).get("from_location").asText()).isEqualTo("A43-0");
    assertThat(instructions.get(3).get("to_location").asText()).isEqualTo("A43-0");
    assertThat(instructions.get(3).get("from_name").asText()).isEqualTo("Card E");
    assertThat(instructions.get(3).get("to_name").asText()).isEqualTo("Card E");
    assertThat(instructions.get(3).get("unit_count").asInt()).isEqualTo(1);
  }

  @Test
  void confirmShouldSkipDiscardAndReviewRows() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportInReview("jordan", "import1", 3);
    createKeepRow("jordan", "import1", 1, "scryfall-1", "normal", "NM", "Keep Card");
    createRowWithDecision("jordan", "import1", 2, "discard", "below threshold");
    createRowWithDecision("jordan", "import1", 3, "review", "unmapped set");

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    processConfirmation("jordan", "import1");
    var body =
        objectMapper.readTree(
            getImportHandler
                .handleRequest(buildEvent("jordan", Map.of("import_id", "import1")), null)
                .getBody());
    assertThat(body.get("unit_count").asInt()).isEqualTo(1);
    assertThat(body.get("total_suggested_price").asText()).isEqualTo("1.50");
  }

  @Test
  void confirmShouldHandleMultipleSkus() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportInReview("jordan", "import1", 4);
    createKeepRow("jordan", "import1", 1, "scryfall-1", "normal", "NM", "Card A");
    createKeepRow("jordan", "import1", 2, "scryfall-2", "foil", "LP", "Card B");
    createKeepRow("jordan", "import1", 3, "scryfall-1", "normal", "NM", "Card C");
    createKeepRow("jordan", "import1", 4, "scryfall-3", "normal", "MP", "Card D");

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    processConfirmation("jordan", "import1");
    var body =
        objectMapper.readTree(
            getImportHandler
                .handleRequest(buildEvent("jordan", Map.of("import_id", "import1")), null)
                .getBody());
    assertThat(body.get("unit_count").asInt()).isEqualTo(4);
    assertThat(body.get("total_suggested_price").asText()).isEqualTo("6.00");

    assertThat(countUnits(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM")))
        .isEqualTo(2);
    assertThat(countUnits(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-2#foil#LP")))
        .isEqualTo(1);
    assertThat(countUnits(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-3#normal#MP")))
        .isEqualTo(1);
  }

  @Test
  void confirmShouldUseInventoryReturnedNoncontiguousPlacements() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportInReview("jordan", "import1", 2);
    var importItem = getImportItem("import1");
    importItem.setStatus("confirming");
    importTable.putItem(importItem);
    createKeepRow("jordan", "import1", 1, "scryfall-1", "normal", "NM", "Card A");
    createKeepRow("jordan", "import1", 2, "scryfall-1", "normal", "NM", "Card B");

    var inventoryService = factory.inventoryService();
    var interleavingService = mock(InventoryService.class);
    when(interleavingService.createUnit(any(RequestContext.class), any(CreateUnitRequest.class)))
        .thenAnswer(
            invocation -> {
              var ctx = invocation.<RequestContext>getArgument(0);
              var request = invocation.<CreateUnitRequest>getArgument(1);
              var created = inventoryService.createUnit(ctx, request);
              if (request.operationId().endsWith(":row:1")) {
                inventoryService.createUnit(
                    ctx,
                    new CreateUnitRequest(
                        "other-operation",
                        "mtg",
                        "interleaved-card",
                        "normal",
                        Condition.NM,
                        "Interleaved card",
                        "dom",
                        "Dominaria",
                        "999",
                        null,
                        null,
                        2624,
                        List.of()));
              }
              return created;
            });
    var processor =
        new ImportConfirmationJobProcessor(
            fakeClock,
            importTable,
            importRowTable,
            new ImportsRepository(factory.dynamoDbClient(), jobTable),
            interleavingService);

    // act
    processor.processBatch(
        "jordan",
        JobItem.create(
            "jordan",
            "confirm-job",
            "import_confirmation",
            "import1",
            null,
            Instant.ofEpochSecond(1700000000)));
    var body =
        objectMapper.readTree(
            getImportHandler
                .handleRequest(buildEvent("jordan", Map.of("import_id", "import1")), null)
                .getBody());

    // assert
    assertThat(getImportRow("import1", 1).getLocation()).isEqualTo("A0-0");
    assertThat(getImportRow("import1", 2).getLocation()).isEqualTo("A0-2");
    var instructions = body.get("placement_instructions");
    assertThat(instructions).hasSize(2);
    assertThat(instructions.get(0).get("from_location").asText()).isEqualTo("A0-0");
    assertThat(instructions.get(0).get("to_location").asText()).isEqualTo("A0-0");
    assertThat(instructions.get(1).get("from_location").asText()).isEqualTo("A0-2");
    assertThat(instructions.get(1).get("to_location").asText()).isEqualTo("A0-2");
    assertThat(instructions.get(0).get("unit_count").asInt()).isEqualTo(1);
    assertThat(instructions.get(1).get("unit_count").asInt()).isEqualTo(1);
  }

  @Test
  void confirmLargeImportShouldCreateEveryUnit() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportInReview("jordan", "import1", 200);
    for (int position = 1; position <= 200; position++) {
      createKeepRow(
          "jordan", "import1", position, "scryfall-1", "normal", "NM", "Card " + position);
    }

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);
    processConfirmation("jordan", "import1");

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    assertThat(countUnits(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM")))
        .isEqualTo(200);
    assertThat(getImportItem("import1").getStatus()).isEqualTo("confirmed");
    assertThat(getAuditEntries("jordan")).hasSize(200);
  }

  @Test
  void confirmShouldSkipRowsWithPersistedCreationResults() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var importItem =
        ImportItem.create(
            "jordan", "mtg", "import1", null, 2, null, Instant.ofEpochSecond(1700000000));
    importItem.setStatus("confirming");
    importTable.putItem(importItem);
    for (int position = 1; position <= 2; position++) {
      var row =
          ImportRowItem.create(
              "jordan",
              "import1",
              position,
              "Card " + position,
              "dom",
              "Dominaria",
              String.valueOf(position),
              "normal",
              "NM",
              "scryfall-1",
              "en");
      row.setDecision("keep");
      row.setSuggestedPrice("1.50");
      row.setSequenceNumber(position - 1);
      row.setSkuId("mtg#scryfall#scryfall-1#normal#NM");
      row.setLocation("A0-" + (position - 1));
      importRowTable.putItem(row);
      unitTable.putItem(
          UnitItem.create(
              "jordan",
              "mtg",
              "mtg#scryfall#scryfall-1#normal#NM",
              position - 1,
              "in_stock",
              "import1",
              Instant.ofEpochSecond(1700000000)));
    }

    // act
    importConfirmationJobProcessor.processBatch(
        "jordan",
        JobItem.create(
            "jordan",
            "confirm-job",
            "import_confirmation",
            "import1",
            null,
            Instant.ofEpochSecond(1700000000)));

    // assert
    assertThat(getImportItem("import1").getStatus()).isEqualTo("confirmed");
    assertThat(countUnits(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM")))
        .isEqualTo(2);
    assertThat(getAuditEntries("jordan")).isEmpty();
  }

  @Test
  void confirmShouldRecoverInventoryCreationWhenRowProgressWriteFails() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var importItem =
        ImportItem.create(
            "jordan", "mtg", "import1", null, 2, null, Instant.ofEpochSecond(1700000000));
    importItem.setStatus("confirming");
    importTable.putItem(importItem);

    var row1 =
        ImportRowItem.create(
            "jordan",
            "import1",
            1,
            "Card A",
            "dom",
            "Dominaria",
            "168",
            "normal",
            "NM",
            "scryfall-1",
            "en");
    row1.setDecision("keep");
    row1.setSuggestedPrice("1.50");
    row1.setFetchtcgCardId("mtg_168_c_dom_normal");
    row1.setFetchtcgSetId(2624);
    row1.setSequenceNumber(0);
    importRowTable.putItem(row1);

    var row2 =
        ImportRowItem.create(
            "jordan",
            "import1",
            2,
            "Card B",
            "dom",
            "Dominaria",
            "169",
            "normal",
            "NM",
            "scryfall-1",
            "en");
    row2.setDecision("keep");
    row2.setSuggestedPrice("1.50");
    row2.setFetchtcgCardId("mtg_168_c_dom_normal");
    row2.setFetchtcgSetId(2624);
    row2.setSequenceNumber(1);
    importRowTable.putItem(row2);

    var jobItem =
        JobItem.create(
            "jordan",
            "confirm-job",
            "import_confirmation",
            "import1",
            null,
            Instant.ofEpochSecond(1700000000));
    jobTable.putItem(jobItem);

    // act
    var repository =
        new ImportsRepository(factory.dynamoDbClient(), jobTable) {
          private int writes;

          @Override
          public void recordCreatedUnit(
              String user,
              String importId,
              int position,
              String skuId,
              int sequenceNumber,
              String location) {
            writes++;
            if (writes == 2) {
              throw new IllegalStateException("progress write failed");
            }
            super.recordCreatedUnit(user, importId, position, skuId, sequenceNumber, location);
          }
        };
    var failingProcessor = createProcessor(repository);
    assertThatThrownBy(() -> failingProcessor.processBatch("jordan", jobItem))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("progress write failed");

    assertThat(getImportItem("import1").getStatus()).isEqualTo("confirming");
    assertThat(getImportRow("import1", 1).getSkuId())
        .isEqualTo("mtg#scryfall#scryfall-1#normal#NM");
    assertThat(getImportRow("import1", 2).getSkuId()).isNull();
    assertThat(countUnits(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM")))
        .isEqualTo(2);
    assertThat(getAuditEntries("jordan")).hasSize(2);

    // act
    importConfirmationJobProcessor.processBatch("jordan", jobItem);

    // assert
    assertThat(getImportItem("import1").getStatus()).isEqualTo("confirmed");
    assertThat(countUnits(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM")))
        .isEqualTo(2);
    assertThat(getAuditEntries("jordan")).hasSize(2);
    assertThat(getImportRow("import1", 2).getSkuId())
        .isEqualTo("mtg#scryfall#scryfall-1#normal#NM");
    assertThat(getImportRow("import1", 2).getSequenceNumber()).isEqualTo(1);
    assertThat(getImportRow("import1", 2).getLocation()).isEqualTo("A0-1");
  }

  @Test
  void confirmShouldReturnZeroTotalsWhenImportHasNoKeepRows() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportInReview("jordan", "import1", 2);
    createRowWithDecision("jordan", "import1", 1, "discard", "below threshold");
    createRowWithDecision("jordan", "import1", 2, "review", "unmapped set");

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    processConfirmation("jordan", "import1");
    var body =
        objectMapper.readTree(
            getImportHandler
                .handleRequest(buildEvent("jordan", Map.of("import_id", "import1")), null)
                .getBody());
    assertThat(body.get("status").asText()).isEqualTo("confirmed");
    assertThat(body.get("unit_count").asInt()).isEqualTo(0);
    assertThat(body.get("total_suggested_price").asText()).isEqualTo("0.00");
    assertThat(body.get("placement_instructions")).isEmpty();
    assertThat(getAuditEntries("jordan")).isEmpty();
  }

  @Test
  void confirmShouldReturn404ForUnknownImport() {
    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "nonexistent")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void confirmShouldReturn409WhenKeepRowsNeedPhotos() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportInReview("jordan", "import1", 2);
    createKeepRow("jordan", "import1", 1, "scryfall-1", "normal", "NM", "Hit A", "20.00", null);
    createKeepRow("jordan", "import1", 2, "scryfall-2", "normal", "NM", "Hit B", "25.00", null);

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("message").asText()).isEqualTo("2 rows need photos before confirm");

    var importResult =
        importTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatUserPk("jordan"))
                .sortValue(ImportItem.formatSk("import1"))
                .build());
    assertThat(importResult.getStatus()).isEqualTo("review");
    assertThat(countUnits(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM")))
        .isEqualTo(0);
    assertThat(countUnits(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-2#normal#NM")))
        .isEqualTo(0);
  }

  @Test
  void confirmShouldCopyRowPhotosOntoUnitsWhenGatedRowsHavePhotos() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportInReview("jordan", "import1", 3);
    createKeepRow(
        "jordan",
        "import1",
        1,
        "scryfall-1",
        "normal",
        "NM",
        "Hit A",
        "20.00",
        List.of(ImportRowItem.Photo.create("photo-a1"), ImportRowItem.Photo.create("photo-a2")));
    createKeepRow(
        "jordan",
        "import1",
        2,
        "scryfall-2",
        "normal",
        "NM",
        "Hit B",
        "25.00",
        List.of(ImportRowItem.Photo.create("photo-b1")));
    createKeepRow(
        "jordan",
        "import1",
        3,
        "scryfall-3",
        "normal",
        "NM",
        "Bulk",
        "1.50",
        List.of(ImportRowItem.Photo.create("photo-c1")));

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    processConfirmation("jordan", "import1");
    var body =
        objectMapper.readTree(
            getImportHandler
                .handleRequest(buildEvent("jordan", Map.of("import_id", "import1")), null)
                .getBody());
    assertThat(body.get("status").asText()).isEqualTo("confirmed");
    assertThat(body.get("unit_count").asInt()).isEqualTo(3);
    assertThat(body.get("total_suggested_price").asText()).isEqualTo("46.50");

    var unitA =
        unitTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM"))
                .sortValue(UnitItem.formatSk(0))
                .build());
    assertThat(unitA.getPhotos()).hasSize(2);
    assertThat(unitA.getPhotos().get(0).getPhotoId()).isEqualTo("photo-a1");
    assertThat(unitA.getPhotos().get(1).getPhotoId()).isEqualTo("photo-a2");

    var unitB =
        unitTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-2#normal#NM"))
                .sortValue(UnitItem.formatSk(1))
                .build());
    assertThat(unitB.getPhotos()).hasSize(1);
    assertThat(unitB.getPhotos().get(0).getPhotoId()).isEqualTo("photo-b1");

    var unitC =
        unitTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-3#normal#NM"))
                .sortValue(UnitItem.formatSk(2))
                .build());
    assertThat(unitC.getPhotos()).hasSize(1);
    assertThat(unitC.getPhotos().get(0).getPhotoId()).isEqualTo("photo-c1");
  }

  private ImportItem getImportItem(String importId) {
    return importTable.getItem(
        Key.builder()
            .partitionValue(ImportItem.formatPk("jordan"))
            .sortValue(ImportItem.formatSk(importId))
            .build());
  }

  private List<AuditItem> getAuditEntries(String user) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(AuditItem.formatPk(user)).build()))
            .build();
    return auditTable.query(request).stream().flatMap(page -> page.items().stream()).toList();
  }

  private List<JobItem> findJobs(String user) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(JobItem.formatPk(user))
                        .sortValue(JobItem.JOB_PREFIX)
                        .build()))
            .build();
    return jobTable.query(request).stream().flatMap(page -> page.items().stream()).toList();
  }

  private void processConfirmation(String user, String importId) throws Exception {
    var jobItem =
        jobTable
            .query(
                QueryEnhancedRequest.builder()
                    .queryConditional(
                        QueryConditional.sortBeginsWith(
                            Key.builder()
                                .partitionValue(JobItem.formatPk(user))
                                .sortValue(JobItem.JOB_PREFIX)
                                .build()))
                    .build())
            .stream()
            .flatMap(page -> page.items().stream())
            .filter(
                item ->
                    "import_confirmation".equals(item.getJobType())
                        && importId.equals(item.getImportId()))
            .findFirst()
            .orElseThrow();
    importConfirmationJobProcessor.processBatch(user, jobItem);
  }

  private ImportConfirmationJobProcessor createProcessor(ImportsRepository repository) {
    return new ImportConfirmationJobProcessor(
        fakeClock, importTable, importRowTable, repository, factory.inventoryService());
  }

  private ImportRowItem getImportRow(String importId, int position) {
    return importRowTable.getItem(
        Key.builder()
            .partitionValue(ImportRowItem.formatPk("jordan", importId))
            .sortValue(ImportRowItem.formatSk(position))
            .build());
  }

  private void createImportInReview(String user, String importId, int rowCount) {
    var importItem =
        ImportItem.create(
            user, "mtg", importId, "test.csv", rowCount, null, Instant.ofEpochSecond(1700000000));
    importItem.setStatus("review");
    importTable.putItem(importItem);
  }

  private void createKeepRow(
      String user,
      String importId,
      int position,
      String scryfallId,
      String finish,
      String condition,
      String name) {
    createKeepRow(user, importId, position, scryfallId, finish, condition, name, "1.50", null);
  }

  private void createKeepRow(
      String user,
      String importId,
      int position,
      String scryfallId,
      String finish,
      String condition,
      String name,
      String suggestedPrice,
      List<ImportRowItem.Photo> photos) {
    var rowItem =
        ImportRowItem.create(
            user,
            importId,
            position,
            name,
            "dom",
            "Dominaria",
            String.valueOf(position),
            finish,
            condition,
            scryfallId,
            "en");
    rowItem.setDecision("keep");
    rowItem.setSuggestedPrice(suggestedPrice);
    rowItem.setFetchtcgCardId("mtg_" + position + "_c_dom_normal");
    rowItem.setFetchtcgSetId(2624);
    if (photos != null) {
      rowItem.setPhotos(photos);
    }
    importRowTable.putItem(rowItem);
  }

  private void createRowWithDecision(
      String user, String importId, int position, String decision, String reason) {
    var rowItem =
        ImportRowItem.create(
            user,
            importId,
            position,
            "Card " + position,
            "dom",
            "Dominaria",
            String.valueOf(position),
            "normal",
            "NM",
            "scryfall-" + position,
            "en");
    rowItem.setDecision(decision);
    rowItem.setDecisionReason(reason);
    importRowTable.putItem(rowItem);
  }

  private long countUnits(String skuPk) {
    var queryConditional =
        QueryConditional.sortBeginsWith(
            Key.builder().partitionValue(skuPk).sortValue(UnitItem.UNIT_PREFIX).build());
    return unitTable
        .query(QueryEnhancedRequest.builder().queryConditional(queryConditional).build())
        .stream()
        .flatMap(page -> page.items().stream())
        .count();
  }

  private APIGatewayV2HTTPEvent buildEvent(String user, Map<String, String> pathParams) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withPathParameters(pathParams)
        .build();
  }
}
