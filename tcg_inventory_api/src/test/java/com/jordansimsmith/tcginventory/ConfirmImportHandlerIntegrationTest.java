package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import java.net.URI;
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

  private static final URI UNUSED_S3_ENDPOINT = URI.create("http://localhost:1");

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);
    var table = factory.tcgInventoryTable();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);

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
    var importItem =
        TcgInventoryItem.createImport(
            "jordan", "import1", null, 1, null, Instant.ofEpochSecond(1700000000));
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
    var importItem =
        TcgInventoryItem.createImport(
            "jordan", "import1", null, 1, null, Instant.ofEpochSecond(1700000000));
    importItem.setStatus("confirmed");
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

    var importItem =
        TcgInventoryItem.createImport(
            "jordan", "import1", null, 2, null, Instant.ofEpochSecond(1700000000));
    importItem.setStatus("confirming");
    tcgInventoryTable.putItem(importItem);

    var row1 =
        TcgInventoryItem.createImportRow(
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
    tcgInventoryTable.putItem(row1);

    var row2 =
        TcgInventoryItem.createImportRow(
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
    tcgInventoryTable.putItem(row2);

    var existingUnit =
        TcgInventoryItem.createUnit(
            "jordan",
            "scryfall-1#normal#NM",
            0,
            "in_stock",
            "import1",
            Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(existingUnit);

    var existingSku =
        TcgInventoryItem.createSku(
            "jordan",
            "scryfall-1#normal#NM",
            "scryfall-1",
            "normal",
            "NM",
            "Card A",
            "dom",
            "Dominaria",
            "168",
            null,
            null);
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
    var importItem =
        TcgInventoryItem.createImport(
            user, importId, "test.csv", rowCount, null, Instant.ofEpochSecond(1700000000));
    importItem.setStatus("review");
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
    var rowItem =
        TcgInventoryItem.createImportRow(
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
    rowItem.setSuggestedPrice("1.50");
    rowItem.setFetchtcgCardId("mtg_" + position + "_c_dom_normal");
    rowItem.setFetchtcgSetId(2624);
    tcgInventoryTable.putItem(rowItem);
  }

  private void createRowWithDecision(
      String user, String importId, int position, String decision, String reason) {
    var rowItem =
        TcgInventoryItem.createImportRow(
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
