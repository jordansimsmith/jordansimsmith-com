package com.jordansimsmith.tcginventory.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
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
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);

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
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("status").asText()).isEqualTo("confirmed");
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
    assertThat(sku1.getVersion()).isEqualTo(1);
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
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
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
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("status").asText()).isEqualTo("confirmed");
    assertThat(body.get("total_suggested_price").asText()).isEqualTo("3.00");

    var sku =
        skuTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM"))
                .sortValue(SkuItem.formatSk())
                .build());
    assertThat(sku.getVersion()).isEqualTo(1);

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
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("status").asText()).isEqualTo("confirmed");
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
    var response =
        confirmImportHandler.handleRequest(
            buildEvent("jordan", Map.of("import_id", "import1")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
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
