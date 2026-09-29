package com.jordansimsmith.tcginventory.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobMessage;
import com.jordansimsmith.tcginventory.JobsHandler;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SequenceCounterItem;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import java.net.URI;
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
  private TcgInventoryTestFactory factory;
  private FakeUlidGenerator fakeUlidGenerator;
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImportItem> importTable;
  private DynamoDbTable<ImportRowItem> importRowTable;
  private DynamoDbTable<SkuItem> skuTable;
  private DynamoDbTable<UnitItem> unitTable;
  private DynamoDbTable<SequenceCounterItem> sequenceCounterTable;

  private ConfirmImportHandler confirmImportHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static final URI UNUSED_S3_ENDPOINT = URI.create("http://localhost:1");

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);
    var table = factory.tableDefinition();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    factory = TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);

    fakeClock = factory.fakeClock();
    fakeUlidGenerator = factory.fakeUlidGenerator();
    objectMapper = factory.objectMapper();
    importTable = factory.importTable();
    importRowTable = factory.importRowTable();
    skuTable = factory.skuTable();
    unitTable = factory.unitTable();
    sequenceCounterTable = factory.sequenceCounterTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeUlidGenerator.reset();

    confirmImportHandler = new ConfirmImportHandler(factory);
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
    var response = confirmImportFully("jordan", "import1");

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = confirmationResult(response);
    assertThat(body.get("unit_count").asInt()).isEqualTo(3);
    assertThat(body.get("total_suggested_price").asText()).isEqualTo("4.50");
    assertThat(body.get("first_sequence_number").asInt()).isEqualTo(0);
    assertThat(body.get("last_sequence_number").asInt()).isEqualTo(2);

    var sku1Pk = SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    var unit0 =
        unitTable.getItem(
            Key.builder().partitionValue(sku1Pk).sortValue(UnitItem.formatSk(0)).build());
    assertThat(unit0).isNotNull();
    assertThat(unit0.getStatus()).isEqualTo("in_stock");
    assertThat(unit0.getImportId()).isEqualTo("import1");
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
    assertThat(sku1.getExternalSource()).isEqualTo("scryfall");
    assertThat(sku1.getExternalId()).isEqualTo("scryfall-1");

    var sequenceCounter =
        sequenceCounterTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatUserPk("jordan"))
                .sortValue(SequenceCounterItem.formatSk("mtg"))
                .build());
    assertThat(sequenceCounter.getGame()).isEqualTo("mtg");
    assertThat(sequenceCounter.getNextSequenceNumber()).isEqualTo(3);

    var sku2Pk = SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-2#foil#LP");
    var sku2 =
        skuTable.getItem(
            Key.builder().partitionValue(sku2Pk).sortValue(SkuItem.formatSk()).build());
    assertThat(countUnits(sku2Pk)).isEqualTo(1);
    assertThat(sku2.getVersion()).isEqualTo(1);
    assertThat(sku2.getDirty()).isTrue();
  }

  @Test
  void confirmShouldQueueTheImportAndReturn202() throws Exception {
    // arrange
    createImportInReview("jordan", "import1", 1);
    createKeepRow("jordan", "import1", 1, "scryfall-1", "normal", "NM", "Card A");

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(202);
    var responseBody = objectMapper.readTree(response.getBody());
    assertThat(responseBody.get("import_id").asText()).isEqualTo("import1");
    assertThat(responseBody.has("status")).isFalse();
    assertThat(factory.fakeJobsQueue().getMessages()).hasSize(1);
    assertThat(factory.fakeJobsQueue().getMessages().get(0).jobType()).isEqualTo("confirm_import");
  }

  @Test
  void confirmShouldProcessMoreThanOneHundredUnitsForOneSku() throws Exception {
    // arrange
    createImportInReview("jordan", "import1", 105);
    for (int position = 1; position <= 105; position++) {
      var row =
          ImportRowItem.create(
              "jordan",
              "import1",
              position,
              "Single SKU Card",
              "dom",
              "Dominaria",
              "1",
              "normal",
              "NM",
              "scryfall",
              "same-printing",
              "en");
      row.setDecision("keep");
      row.setSuggestedPrice("1.50");
      row.setFetchtcgCardId("mtg_same_card");
      row.setFetchtcgSetId(2624);
      importRowTable.putItem(row);
    }

    // act
    var response = confirmImportFully("jordan", "import1");

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(countUnits(SkuItem.formatPk("jordan", "mtg#scryfall#same-printing#normal#NM")))
        .isEqualTo(105);
    var sku =
        skuTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatPk("jordan", "mtg#scryfall#same-printing#normal#NM"))
                .sortValue(SkuItem.formatSk())
                .build());
    assertThat(sku.getVersion()).isEqualTo(105);
    assertThat(
            importRowTable
                .getItem(
                    Key.builder()
                        .partitionValue(ImportRowItem.formatPk("jordan", "import1"))
                        .sortValue(ImportRowItem.formatSk(105))
                        .build())
                .getConfirmed())
        .isTrue();
  }

  @Test
  void confirmShouldResumeAtTheFrozenSequenceRangeAndSkipCompletedRows() throws Exception {
    // arrange
    createImportInReview("jordan", "import1", 2);
    var importItem =
        importTable.getItem(
            Key.builder()
                .partitionValue(ImportItem.formatPk("jordan"))
                .sortValue(ImportItem.formatSk("import1"))
                .build());
    importItem.setStatus("confirming");
    importItem.setFirstSequenceNumber(500);
    importItem.setConfirmationUnitCount(2);
    importTable.putItem(importItem);
    createKeepRow("jordan", "import1", 1, "scryfall-1", "normal", "NM", "Card A");
    createKeepRow("jordan", "import1", 2, "scryfall-2", "normal", "NM", "Card B");
    var completedRow =
        importRowTable.getItem(
            Key.builder()
                .partitionValue(ImportRowItem.formatPk("jordan", "import1"))
                .sortValue(ImportRowItem.formatSk(1))
                .build());
    completedRow.setConfirmed(true);
    completedRow.setSequenceNumber(500);
    importRowTable.putItem(completedRow);
    var completedUnit =
        UnitItem.create(
            "jordan",
            "mtg",
            "mtg#scryfall#scryfall-1#normal#NM",
            500,
            "in_stock",
            "import1",
            Instant.ofEpochSecond(1700000000));
    unitTable.putItem(completedUnit);
    skuTable.putItem(
        SkuItem.create(
            "jordan",
            "mtg#scryfall#scryfall-1#normal#NM",
            "mtg",
            "scryfall",
            "scryfall-1",
            "normal",
            "NM",
            "Card A",
            "dom",
            "Dominaria",
            "1",
            null,
            null));

    // act
    var response = confirmImportFully("jordan", "import1");

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(confirmationResult(response).get("first_sequence_number").asInt()).isEqualTo(500);
    assertThat(
            unitTable.getItem(
                Key.builder()
                    .partitionValue(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-2#normal#NM"))
                    .sortValue(UnitItem.formatSk(501))
                    .build()))
        .isNotNull();
  }

  @Test
  void confirmShouldFailWhenAnExistingUnitHasDifferentImportProvenance() {
    // arrange
    createImportInReview("jordan", "import1", 1);
    createKeepRow("jordan", "import1", 1, "scryfall-1", "normal", "NM", "Card A");
    unitTable.putItem(
        UnitItem.create(
            "jordan",
            "mtg",
            "mtg#scryfall#scryfall-1#normal#NM",
            0,
            "in_stock",
            "other-import",
            Instant.ofEpochSecond(1700000000)));

    // act / assert
    assertThatThrownBy(() -> confirmImportFully("jordan", "import1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts with existing inventory");
  }

  @Test
  void confirmShouldResumeAfterFailedJobAndReturnCompletedResult() throws Exception {
    // arrange
    createImportInReview("jordan", "import1", 1);
    createKeepRow("jordan", "import1", 1, "scryfall-1", "normal", "NM", "Card A");
    unitTable.putItem(
        UnitItem.create(
            "jordan",
            "mtg",
            "mtg#scryfall#scryfall-1#normal#NM",
            0,
            "in_stock",
            "other-import",
            Instant.ofEpochSecond(1700000000)));

    // act
    var firstResponse =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);
    var firstJobId = factory.fakeJobsQueue().getMessages().getFirst().jobId();
    var firstDeduplicationId =
        factory.fakeJobsQueue().getSends().getFirst().messageDeduplicationId();
    runJob(firstJobId, "confirm_import");
    var failedImport =
        importTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatUserPk("jordan"))
                .sortValue(ImportItem.formatSk("import1"))
                .build());
    var failedJob =
        factory
            .jobTable()
            .getItem(
                Key.builder()
                    .partitionValue(JobItem.formatPk("jordan"))
                    .sortValue(JobItem.formatSk(firstJobId))
                    .build());
    unitTable.deleteItem(
        Key.builder()
            .partitionValue(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM"))
            .sortValue(UnitItem.formatSk(0))
            .build());
    var retryResponse =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);
    var retryJobId = factory.fakeJobsQueue().getMessages().getLast().jobId();
    var retryDeduplicationId =
        factory.fakeJobsQueue().getSends().getLast().messageDeduplicationId();
    runJob(retryJobId, "confirm_import");
    var completedResponse =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);
    var importDetail =
        new GetImportHandler(factory)
            .handleRequest(buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(firstResponse.getStatusCode()).isEqualTo(202);
    assertThat(failedImport.getStatus()).isEqualTo("confirming");
    assertThat(objectMapper.readTree(firstResponse.getBody()).has("job_id")).isFalse();
    assertThat(objectMapper.readTree(importDetail.getBody()).get("confirmation_error").asText())
        .isNotBlank();
    assertThat(failedJob.getStatus()).isEqualTo("failed");
    assertThat(retryResponse.getStatusCode()).isEqualTo(202);
    assertThat(retryJobId).isEqualTo(firstJobId);
    assertThat(retryDeduplicationId).isNotEqualTo(firstDeduplicationId);
    assertThat(completedResponse.getStatusCode()).isEqualTo(200);
    var completedBody = objectMapper.readTree(completedResponse.getBody());
    assertThat(completedBody.get("import_id").asText()).isEqualTo("import1");
    assertThat(completedBody.has("status")).isFalse();
    assertThat(
            unitTable.getItem(
                Key.builder()
                    .partitionValue(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM"))
                    .sortValue(UnitItem.formatSk(0))
                    .build()))
        .isNotNull();
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
  void confirmShouldReturn409OnDoubleConfirm() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var importItem =
        ImportItem.create(
            "jordan", "mtg", "import1", null, 1, null, Instant.ofEpochSecond(1700000000));
    importItem.setStatus("confirmed");
    importTable.putItem(importItem);

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
  }

  @Test
  void confirmShouldReturnPlacementInstructions() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportInReview("jordan", "import1", 2);
    createKeepRow("jordan", "import1", 1, "scryfall-1", "normal", "NM", "Sol Ring");
    createKeepRow("jordan", "import1", 2, "scryfall-2", "normal", "NM", "Lightning Bolt");

    // act
    var response = confirmImportFully("jordan", "import1");

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = confirmationResult(response);
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
  void getShouldReturnPlacementInstructionsForConfirmedLegacyImport() throws Exception {
    // arrange
    var importItem =
        ImportItem.create(
            "jordan", "mtg", "import1", "test.csv", 2, null, Instant.ofEpochSecond(1700000000));
    importItem.setStatus("confirmed");
    importTable.putItem(importItem);
    createKeepRow("jordan", "import1", 1, "scryfall-1", "normal", "NM", "Card A");
    createKeepRow("jordan", "import1", 2, "scryfall-2", "normal", "NM", "Card B");
    for (int position = 1; position <= 2; position++) {
      var row =
          importRowTable.getItem(
              Key.builder()
                  .partitionValue(ImportRowItem.formatPk("jordan", "import1"))
                  .sortValue(ImportRowItem.formatSk(position))
                  .build());
      row.setSequenceNumber(5200 + position - 1);
      importRowTable.putItem(row);
    }

    // act
    var response =
        new GetImportHandler(factory)
            .handleRequest(buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("status").asText()).isEqualTo("confirmed");
    var result = body.get("confirmation_result");
    assertThat(result.get("unit_count").asInt()).isEqualTo(2);
    assertThat(result.get("first_sequence_number").asInt()).isEqualTo(5200);
    assertThat(result.get("last_sequence_number").asInt()).isEqualTo(5201);
    assertThat(result.get("placement_instructions").get(0).get("from_name").asText())
        .isEqualTo("Card A");
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
    var response = confirmImportFully("jordan", "import1");

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = confirmationResult(response);
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
    var response = confirmImportFully("jordan", "import1");

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = confirmationResult(response);
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
  void confirmShouldBeIdempotentOnReplay() throws Exception {
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
            "scryfall",
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
            "scryfall",
            "scryfall-1",
            "en");
    row2.setDecision("keep");
    row2.setSuggestedPrice("1.50");
    row2.setFetchtcgCardId("mtg_168_c_dom_normal");
    row2.setFetchtcgSetId(2624);
    row2.setSequenceNumber(1);
    importRowTable.putItem(row2);

    var existingUnit =
        UnitItem.create(
            "jordan",
            "mtg",
            "mtg#scryfall#scryfall-1#normal#NM",
            0,
            "in_stock",
            "import1",
            Instant.ofEpochSecond(1700000000));
    unitTable.putItem(existingUnit);

    var existingSku =
        SkuItem.create(
            "jordan",
            "mtg#scryfall#scryfall-1#normal#NM",
            "mtg",
            "scryfall",
            "scryfall-1",
            "normal",
            "NM",
            "Card A",
            "dom",
            "Dominaria",
            "168",
            null,
            null);
    skuTable.putItem(existingSku);

    // act
    var response = confirmImportFully("jordan", "import1");

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = confirmationResult(response);
    assertThat(body.get("total_suggested_price").asText()).isEqualTo("3.00");

    var sku =
        skuTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM"))
                .sortValue(SkuItem.formatSk())
                .build());
    assertThat(sku.getVersion()).isEqualTo(2);

    var importResult =
        importTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatUserPk("jordan"))
                .sortValue(ImportItem.formatSk("import1"))
                .build());
    assertThat(importResult.getStatus()).isEqualTo("confirmed");
  }

  @Test
  void confirmShouldReturnZeroTotalsWhenImportHasNoKeepRows() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportInReview("jordan", "import1", 2);
    createRowWithDecision("jordan", "import1", 1, "discard", "below threshold");
    createRowWithDecision("jordan", "import1", 2, "review", "unmapped set");

    // act
    var response = confirmImportFully("jordan", "import1");

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = confirmationResult(response);
    assertThat(body.get("unit_count").asInt()).isEqualTo(0);
    assertThat(body.get("total_suggested_price").asText()).isEqualTo("0.00");
    assertThat(body.get("placement_instructions")).isEmpty();
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

  private APIGatewayV2HTTPResponse confirmImportFully(String user, String importId) {
    var acceptedResponse =
        confirmImportHandler.handleRequest(buildEvent(user, Map.of("import_id", importId)), null);
    assertThat(acceptedResponse.getStatusCode()).isEqualTo(202);

    var inventoryRepository =
        new InventoryRepository(unitTable, factory.dynamoDbClient(), fakeClock, fakeUlidGenerator);
    var importRepository =
        new ImportRepository(
            importTable,
            importRowTable,
            factory.jobTable(),
            inventoryRepository,
            factory.dynamoDbClient(),
            fakeClock);
    var processor = new ConfirmImportJobProcessor(importRepository, fakeClock);
    var jobMessage = factory.fakeJobsQueue().getMessages().getFirst();
    var jobItem =
        factory
            .jobTable()
            .getItem(
                Key.builder()
                    .partitionValue(JobItem.formatPk(user))
                    .sortValue(JobItem.formatSk(jobMessage.jobId()))
                    .build());
    processor.processBatch(user, jobItem);
    return new GetImportHandler(factory)
        .handleRequest(buildEvent(user, Map.of("import_id", importId)), null);
  }

  private JsonNode confirmationResult(APIGatewayV2HTTPResponse response) {
    try {
      return objectMapper.readTree(response.getBody()).get("confirmation_result");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void runJob(String jobId, String jobType) throws Exception {
    var record = new SQSEvent.SQSMessage();
    record.setBody(objectMapper.writeValueAsString(new JobMessage("jordan", jobId, jobType)));
    var event = new SQSEvent();
    event.setRecords(List.of(record));
    new JobsHandler(factory).handleRequest(event, null);
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
        List.of(
            ImportRowItem.Photo.create("photo-a1", null),
            ImportRowItem.Photo.create("photo-a2", null)));
    createKeepRow(
        "jordan",
        "import1",
        2,
        "scryfall-2",
        "normal",
        "NM",
        "Hit B",
        "25.00",
        List.of(ImportRowItem.Photo.create("photo-b1", null)));
    createKeepRow(
        "jordan",
        "import1",
        3,
        "scryfall-3",
        "normal",
        "NM",
        "Bulk",
        "1.50",
        List.of(ImportRowItem.Photo.create("photo-c1", null)));

    // act
    var response = confirmImportFully("jordan", "import1");

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = confirmationResult(response);
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
            "scryfall",
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
            "scryfall",
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
