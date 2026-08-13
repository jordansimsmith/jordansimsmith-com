package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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
  private ObjectMapper objectMapper;
  private DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

  private ConfirmImportHandler confirmImportHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint());
    var table = factory.tcgInventoryTable();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory = TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeUlidGenerator = factory.fakeUlidGenerator();
    objectMapper = factory.objectMapper();
    tcgInventoryTable = factory.tcgInventoryTable();

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
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("status").asText()).isEqualTo("confirmed");
    assertThat(body.get("unit_count").asInt()).isEqualTo(3);
    assertThat(body.get("first_sequence_number").asInt()).isEqualTo(0);
    assertThat(body.get("last_sequence_number").asInt()).isEqualTo(2);

    var sku1Pk = TcgInventoryItem.formatSkuPk("jordan", "scryfall-1#normal#NM");
    var unit0 =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(sku1Pk)
                .sortValue(TcgInventoryItem.formatUnitSk(0))
                .build());
    assertThat(unit0).isNotNull();
    assertThat(unit0.getStatus()).isEqualTo("in_stock");
    assertThat(unit0.getImportId()).isEqualTo("import1");

    var unit1 =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(sku1Pk)
                .sortValue(TcgInventoryItem.formatUnitSk(1))
                .build());
    assertThat(unit1).isNotNull();

    var sku1 =
        tcgInventoryTable.getItem(
            Key.builder().partitionValue(sku1Pk).sortValue(TcgInventoryItem.formatSkuSk()).build());
    assertThat(countUnits(sku1Pk)).isEqualTo(2);
    assertThat(sku1.getVersion()).isEqualTo(1);
    assertThat(sku1.getDirty()).isTrue();
    assertThat(sku1.getGsi1pk()).isEqualTo(TcgInventoryItem.formatGsi1pk("jordan"));

    var sku2Pk = TcgInventoryItem.formatSkuPk("jordan", "scryfall-2#foil#LP");
    var sku2 =
        tcgInventoryTable.getItem(
            Key.builder().partitionValue(sku2Pk).sortValue(TcgInventoryItem.formatSkuSk()).build());
    assertThat(countUnits(sku2Pk)).isEqualTo(1);
    assertThat(sku2.getVersion()).isEqualTo(1);
    assertThat(sku2.getDirty()).isTrue();
  }

  @Test
  void confirmShouldReturn409WhenNotInReview() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var importItem = new TcgInventoryItem();
    importItem.setPk(TcgInventoryItem.formatUserPk("jordan"));
    importItem.setSk(TcgInventoryItem.formatImportSk("import1"));
    importItem.setImportId("import1");
    importItem.setStatus("appraising");
    importItem.setRowCount(1);
    importItem.setCreatedAt(Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(importItem);

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
    var importItem = new TcgInventoryItem();
    importItem.setPk(TcgInventoryItem.formatUserPk("jordan"));
    importItem.setSk(TcgInventoryItem.formatImportSk("import1"));
    importItem.setImportId("import1");
    importItem.setStatus("confirmed");
    importItem.setRowCount(1);
    importItem.setCreatedAt(Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(importItem);

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
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
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
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
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("unit_count").asInt()).isEqualTo(1);
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
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("unit_count").asInt()).isEqualTo(4);

    assertThat(countUnits(TcgInventoryItem.formatSkuPk("jordan", "scryfall-1#normal#NM")))
        .isEqualTo(2);
    assertThat(countUnits(TcgInventoryItem.formatSkuPk("jordan", "scryfall-2#foil#LP")))
        .isEqualTo(1);
    assertThat(countUnits(TcgInventoryItem.formatSkuPk("jordan", "scryfall-3#normal#MP")))
        .isEqualTo(1);
  }

  @Test
  void confirmShouldBeIdempotentOnReplay() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));

    var importItem = new TcgInventoryItem();
    importItem.setPk(TcgInventoryItem.formatUserPk("jordan"));
    importItem.setSk(TcgInventoryItem.formatImportSk("import1"));
    importItem.setImportId("import1");
    importItem.setStatus("confirming");
    importItem.setRowCount(2);
    importItem.setKeepCount(0);
    importItem.setDiscardCount(0);
    importItem.setReviewCount(0);
    importItem.setCreatedAt(Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(importItem);

    var row1 = new TcgInventoryItem();
    row1.setPk(TcgInventoryItem.formatImportRowPk("jordan", "import1"));
    row1.setSk(TcgInventoryItem.formatImportRowSk(1));
    row1.setPosition(1);
    row1.setName("Card A");
    row1.setSetCode("dom");
    row1.setSetName("Dominaria");
    row1.setCollectorNumber("168");
    row1.setFinish("normal");
    row1.setCondition("NM");
    row1.setScryfallId("scryfall-1");
    row1.setLanguage("en");
    row1.setDecision("keep");
    row1.setSequenceNumber(0);
    tcgInventoryTable.putItem(row1);

    var row2 = new TcgInventoryItem();
    row2.setPk(TcgInventoryItem.formatImportRowPk("jordan", "import1"));
    row2.setSk(TcgInventoryItem.formatImportRowSk(2));
    row2.setPosition(2);
    row2.setName("Card B");
    row2.setSetCode("dom");
    row2.setSetName("Dominaria");
    row2.setCollectorNumber("169");
    row2.setFinish("normal");
    row2.setCondition("NM");
    row2.setScryfallId("scryfall-1");
    row2.setLanguage("en");
    row2.setDecision("keep");
    row2.setSequenceNumber(1);
    tcgInventoryTable.putItem(row2);

    var existingUnit = new TcgInventoryItem();
    existingUnit.setPk(TcgInventoryItem.formatSkuPk("jordan", "scryfall-1#normal#NM"));
    existingUnit.setSk(TcgInventoryItem.formatUnitSk(0));
    existingUnit.setSequenceNumber(0);
    existingUnit.setStatus("in_stock");
    existingUnit.setImportId("import1");
    tcgInventoryTable.putItem(existingUnit);

    var existingSku = new TcgInventoryItem();
    existingSku.setPk(TcgInventoryItem.formatSkuPk("jordan", "scryfall-1#normal#NM"));
    existingSku.setSk(TcgInventoryItem.formatSkuSk());
    existingSku.setSkuId("scryfall-1#normal#NM");
    existingSku.setVersion(1);
    existingSku.setDirty(true);
    tcgInventoryTable.putItem(existingSku);

    // act
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("status").asText()).isEqualTo("confirmed");

    var sku =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatSkuPk("jordan", "scryfall-1#normal#NM"))
                .sortValue(TcgInventoryItem.formatSkuSk())
                .build());
    assertThat(sku.getVersion()).isEqualTo(1);

    var importResult =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatImportSk("import1"))
                .build());
    assertThat(importResult.getStatus()).isEqualTo("confirmed");
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

  private void createImportInReview(String user, String importId, int rowCount) {
    var importItem = new TcgInventoryItem();
    importItem.setPk(TcgInventoryItem.formatUserPk(user));
    importItem.setSk(TcgInventoryItem.formatImportSk(importId));
    importItem.setImportId(importId);
    importItem.setFilename("test.csv");
    importItem.setStatus("review");
    importItem.setRowCount(rowCount);
    importItem.setKeepCount(0);
    importItem.setDiscardCount(0);
    importItem.setReviewCount(0);
    importItem.setCreatedAt(Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(importItem);
  }

  private void createKeepRow(
      String user,
      String importId,
      int position,
      String scryfallId,
      String finish,
      String condition,
      String name) {
    var rowItem = new TcgInventoryItem();
    rowItem.setPk(TcgInventoryItem.formatImportRowPk(user, importId));
    rowItem.setSk(TcgInventoryItem.formatImportRowSk(position));
    rowItem.setPosition(position);
    rowItem.setName(name);
    rowItem.setSetCode("dom");
    rowItem.setSetName("Dominaria");
    rowItem.setCollectorNumber(String.valueOf(position));
    rowItem.setFinish(finish);
    rowItem.setCondition(condition);
    rowItem.setScryfallId(scryfallId);
    rowItem.setLanguage("en");
    rowItem.setDecision("keep");
    tcgInventoryTable.putItem(rowItem);
  }

  private void createRowWithDecision(
      String user, String importId, int position, String decision, String reason) {
    var rowItem = new TcgInventoryItem();
    rowItem.setPk(TcgInventoryItem.formatImportRowPk(user, importId));
    rowItem.setSk(TcgInventoryItem.formatImportRowSk(position));
    rowItem.setPosition(position);
    rowItem.setName("Card " + position);
    rowItem.setSetCode("dom");
    rowItem.setSetName("Dominaria");
    rowItem.setCollectorNumber(String.valueOf(position));
    rowItem.setFinish("normal");
    rowItem.setCondition("NM");
    rowItem.setScryfallId("scryfall-" + position);
    rowItem.setLanguage("en");
    rowItem.setDecision(decision);
    rowItem.setDecisionReason(reason);
    tcgInventoryTable.putItem(rowItem);
  }

  private long countUnits(String skuPk) {
    var queryConditional =
        QueryConditional.sortBeginsWith(
            Key.builder().partitionValue(skuPk).sortValue(TcgInventoryItem.UNIT_PREFIX).build());
    return tcgInventoryTable
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
