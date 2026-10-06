package com.jordansimsmith.tcginventory.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.tcginventory.AuditItem;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.Photos;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
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
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

@Testcontainers
public class InventoryHandlerIntegrationTest {

  private FakeClock fakeClock;
  private FakeUlidGenerator fakeUlidGenerator;
  private ObjectMapper objectMapper;
  private DynamoDbClient dynamoDbClient;
  private DynamoDbTable<SkuItem> skuTable;
  private DynamoDbTable<UnitItem> unitTable;
  private DynamoDbTable<AuditItem> auditTable;
  private DynamoDbTable<JobItem> jobTable;
  private InventoryRepository inventoryRepository;

  private FindSkusHandler findSkusHandler;
  private GetSkuHandler getSkuHandler;
  private RemoveUnitHandler removeUnitHandler;
  private UpdateUnitHandler updateUnitHandler;

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
    dynamoDbClient = factory.dynamoDbClient();
    skuTable = factory.skuTable();
    unitTable = factory.unitTable();
    auditTable = factory.auditTable();
    jobTable = factory.jobTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeUlidGenerator.reset();

    findSkusHandler = new FindSkusHandler(factory);
    getSkuHandler = new GetSkuHandler(factory);
    removeUnitHandler = new RemoveUnitHandler(factory);
    updateUnitHandler = new UpdateUnitHandler(factory);
    inventoryRepository =
        new InventoryRepository(unitTable, factory.dynamoDbClient(), fakeClock, fakeUlidGenerator);
  }

  @Test
  void findSkusShouldReturnSkusAlphabetically() throws Exception {
    // arrange
    createSku(
        "jordan", "mtg#scryfall#scryfall-z#normal#NM", "Zombie Knight", "dom", "Dominaria", "100");
    createSku(
        "jordan", "mtg#scryfall#scryfall-a#normal#NM", "Angel of Mercy", "dom", "Dominaria", "101");
    createSku(
        "jordan", "mtg#scryfall#scryfall-e#foil#LP", "Elvish Mystic", "m14", "Magic 2014", "169");

    // act
    var response =
        findSkusHandler.handleRequest(
            buildEventWithQuery("jordan", Map.of(), Map.of("game", "mtg")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var skus = body.get("skus");
    assertThat(skus).hasSize(3);
    assertThat(skus.get(0).get("name").asText()).isEqualTo("Angel of Mercy");
    assertThat(skus.get(1).get("name").asText()).isEqualTo("Elvish Mystic");
    assertThat(skus.get(2).get("name").asText()).isEqualTo("Zombie Knight");
    assertThat(skus.get(1).get("finish").asText()).isEqualTo("foil");
    assertThat(skus.get(1).get("condition").asText()).isEqualTo("LP");
    assertThat(skus.get(1).get("game").asText()).isEqualTo("mtg");
    assertThat(body.get("next_continuation").isNull()).isTrue();
  }

  @Test
  void findSkusShouldRequireSupportedGame() throws Exception {
    // act
    var missingGame = findSkusHandler.handleRequest(buildEvent("jordan", Map.of()), null);
    var unsupportedGame =
        findSkusHandler.handleRequest(
            buildEventWithQuery("jordan", Map.of(), Map.of("game", "pokemon")), null);

    // assert
    assertThat(missingGame.getStatusCode()).isEqualTo(400);
    assertThat(objectMapper.readTree(missingGame.getBody()).get("message").asText())
        .isEqualTo("game is required");
    assertThat(unsupportedGame.getStatusCode()).isEqualTo(400);
    assertThat(objectMapper.readTree(unsupportedGame.getBody()).get("message").asText())
        .isEqualTo("unsupported game: pokemon");
  }

  @Test
  void findSkusShouldSupportPrefixSearch() throws Exception {
    // arrange
    createSku(
        "jordan", "mtg#scryfall#scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createSku(
        "jordan",
        "mtg#scryfall#scryfall-2#normal#NM",
        "Elvish Aberration",
        "a25",
        "Masters 25",
        "167");
    createSku(
        "jordan", "mtg#scryfall#scryfall-3#normal#NM", "Sol Ring", "c21", "Commander 2021", "167");

    // act
    var response =
        findSkusHandler.handleRequest(
            buildEventWithQuery("jordan", Map.of(), Map.of("game", "mtg", "search", "elv")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var skus = body.get("skus");
    assertThat(skus).hasSize(2);
    assertThat(skus.get(0).get("name").asText()).isEqualTo("Elvish Aberration");
    assertThat(skus.get(1).get("name").asText()).isEqualTo("Elvish Mystic");
  }

  @Test
  void findSkusShouldSupportContinuationPaging() throws Exception {
    // arrange
    createSku("jordan", "mtg#scryfall#scryfall-1#normal#NM", "Alpha Card", "dom", "Dominaria", "1");
    createSku("jordan", "mtg#scryfall#scryfall-2#normal#NM", "Beta Card", "dom", "Dominaria", "2");
    createSku("jordan", "mtg#scryfall#scryfall-3#normal#NM", "Gamma Card", "dom", "Dominaria", "3");

    // act - first page
    var response1 =
        findSkusHandler.handleRequest(
            buildEventWithQuery("jordan", Map.of(), Map.of("game", "mtg", "limit", "2")), null);

    // assert - first page
    assertThat(response1.getStatusCode()).isEqualTo(200);
    var body1 = objectMapper.readTree(response1.getBody());
    assertThat(body1.get("skus")).hasSize(2);
    assertThat(body1.get("skus").get(0).get("name").asText()).isEqualTo("Alpha Card");
    assertThat(body1.get("skus").get(1).get("name").asText()).isEqualTo("Beta Card");
    var continuation = body1.get("next_continuation").asText();
    assertThat(continuation).isNotNull();
    assertThat(continuation).isNotEmpty();

    // act - second page
    var response2 =
        findSkusHandler.handleRequest(
            buildEventWithQuery(
                "jordan", Map.of(), Map.of("game", "mtg", "continuation", continuation)),
            null);

    // assert - second page
    assertThat(response2.getStatusCode()).isEqualTo(200);
    var body2 = objectMapper.readTree(response2.getBody());
    assertThat(body2.get("skus")).hasSize(1);
    assertThat(body2.get("skus").get(0).get("name").asText()).isEqualTo("Gamma Card");
    assertThat(body2.get("next_continuation").isNull()).isTrue();
  }

  @Test
  void getSkuShouldReturnDetailWithUnitsSortedBySequenceNumber() throws Exception {
    // arrange
    createSku(
        "jordan", "mtg#scryfall#scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    var skuKey =
        Map.of(
            SkuItem.PK,
            AttributeValue.builder()
                .s(SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM"))
                .build(),
            SkuItem.SK,
            AttributeValue.builder().s(SkuItem.formatSk()).build());
    var newlyCreatedSku =
        dynamoDbClient.getItem(
            GetItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(skuKey)
                .consistentRead(true)
                .build());
    assertThat(newlyCreatedSku.item()).doesNotContainKey("external_source");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 4242, "in_stock", "import1");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1204, "reserved", "import1");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 4250, "in_stock", "import1");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 500, "sold", "import1");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 300, "removed", "import1");

    // act
    var response =
        getSkuHandler.handleRequest(
            buildEvent("jordan", Map.of("sku_id", "mtg#scryfall#scryfall-1#normal#NM")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("sku_id").asText()).isEqualTo("mtg#scryfall#scryfall-1#normal#NM");
    assertThat(body.get("game").asText()).isEqualTo("mtg");
    assertThat(body.has("external_source")).isFalse();
    assertThat(body.get("external_id").asText()).isEqualTo("scryfall-1");
    assertThat(body.get("image_urls").get("small").asText())
        .isEqualTo("https://img.example/cards/scryfall-1/small.jpg");
    assertThat(body.get("image_urls").get("normal").asText())
        .isEqualTo("https://img.example/cards/scryfall-1/normal.jpg");
    assertThat(body.has("scryfall_id")).isFalse();
    assertThat(body.get("name").asText()).isEqualTo("Elvish Mystic");
    assertThat(body.get("in_stock_count").asInt()).isEqualTo(2);
    assertThat(body.get("reserved_count").asInt()).isEqualTo(1);
    assertThat(body.get("sold_count").asInt()).isEqualTo(1);

    var units = body.get("units");
    assertThat(units).hasSize(4);
    assertThat(units.get(0).get("sequence_number").asInt()).isEqualTo(500);
    assertThat(units.get(0).get("status").asText()).isEqualTo("sold");
    assertThat(units.get(1).get("sequence_number").asInt()).isEqualTo(1204);
    assertThat(units.get(1).get("location").asText()).isEqualTo("A12-4");
    assertThat(units.get(2).get("sequence_number").asInt()).isEqualTo(4242);
    assertThat(units.get(2).get("location").asText()).isEqualTo("A42-42");
    assertThat(units.get(3).get("sequence_number").asInt()).isEqualTo(4250);
  }

  @Test
  void getSkuShouldDecodeUrlEncodedSkuId() throws Exception {
    // arrange
    createSku(
        "jordan", "mtg#scryfall#scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 4242, "in_stock", "import1");

    // act
    var response =
        getSkuHandler.handleRequest(
            buildEvent("jordan", Map.of("sku_id", "mtg%23scryfall%23scryfall-1%23normal%23NM")),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("sku_id").asText()).isEqualTo("mtg#scryfall#scryfall-1#normal#NM");
  }

  @Test
  void getSkuShouldReturn404ForUnknownSku() {
    // act
    var response =
        getSkuHandler.handleRequest(
            buildEvent("jordan", Map.of("sku_id", "mtg#scryfall#nonexistent#normal#NM")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void removeUnitShouldSetStatusToRemovedAndDirtySku() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createSku(
        "jordan", "mtg#scryfall#scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 42, "in_stock", "import1");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 43, "in_stock", "import1");

    // act
    var response =
        removeUnitHandler.handleRequest(
            buildEventWithQuery(
                "jordan",
                Map.of("sku_id", "mtg#scryfall#scryfall-1#normal#NM", "sequence_number", "42"),
                Map.of("reason", "damaged")),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);

    var skuPk = SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    var sku =
        skuTable.getItem(Key.builder().partitionValue(skuPk).sortValue(SkuItem.formatSk()).build());
    assertThat(sku.getDirty()).isTrue();
    assertThat(sku.getVersion()).isEqualTo(2);
    assertThat(sku.getGsi1pk()).isEqualTo(SkuItem.formatGsi1pk("jordan"));

    var unit =
        unitTable.getItem(
            Key.builder().partitionValue(skuPk).sortValue(UnitItem.formatSk(42)).build());
    assertThat(unit.getStatus()).isEqualTo("removed");

    var auditItems = queryAuditEntries("jordan");
    assertThat(auditItems).hasSize(1);
    assertThat(auditItems.get(0).getEventType()).isEqualTo("adjustment");
    assertThat(auditItems.get(0).getSkuId()).isEqualTo("mtg#scryfall#scryfall-1#normal#NM");
    assertThat(auditItems.get(0).getSequenceNumber()).isEqualTo(42);
    assertThat(auditItems.get(0).getBeforeStatus()).isEqualTo("in_stock");
    assertThat(auditItems.get(0).getAfterStatus()).isEqualTo("removed");
    assertThat(auditItems.get(0).getDecisionReason()).isEqualTo("damaged");
  }

  @Test
  void removeUnitShouldReturn409WhenAJobIsActive() throws Exception {
    // arrange
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSku("jordan", skuId, "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", skuId, 42, "in_stock", "import1");
    jobTable.putItem(
        JobItem.create("jordan", "job1", "publish", null, null, Instant.ofEpochSecond(1700000000)));

    // act
    var response =
        removeUnitHandler.handleRequest(
            buildEvent("jordan", Map.of("sku_id", skuId, "sequence_number", "42")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("another job is in progress");
    assertThat(getUnit("jordan", skuId, 42).getStatus()).isEqualTo("in_stock");
    assertThat(queryAuditEntries("jordan")).isEmpty();
  }

  @Test
  void removeUnitShouldRollBackSkuAndAuditWhenUnitIsNoLongerInStock() {
    // arrange
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSku("jordan", skuId, "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", skuId, 42, "reserved", "import1");

    // act
    assertThatThrownBy(() -> inventoryRepository.removeUnit("jordan", skuId, 42, null))
        .isInstanceOf(RuntimeException.class);

    // assert
    var sku = getSku("jordan", skuId);
    assertThat(sku.getDirty()).isFalse();
    assertThat(sku.getVersion()).isEqualTo(1);
    assertThat(getUnit("jordan", skuId, 42).getStatus()).isEqualTo("reserved");
    assertThat(queryAuditEntries("jordan")).isEmpty();
  }

  @Test
  void removeUnitShouldReturn404ForMissingUnit() {
    // arrange
    createSku(
        "jordan", "mtg#scryfall#scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");

    // act
    var response =
        removeUnitHandler.handleRequest(
            buildEvent(
                "jordan",
                Map.of("sku_id", "mtg#scryfall#scryfall-1#normal#NM", "sequence_number", "99")),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void removeUnitShouldReturn409ForNonInStockUnit() {
    // arrange
    createSku(
        "jordan", "mtg#scryfall#scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 42, "reserved", "import1");

    // act
    var response =
        removeUnitHandler.handleRequest(
            buildEvent(
                "jordan",
                Map.of("sku_id", "mtg#scryfall#scryfall-1#normal#NM", "sequence_number", "42")),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
  }

  @Test
  void updateUnitShouldMoveUnitBetweenSkus() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createSku(
        "jordan", "mtg#scryfall#scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createSku(
        "jordan", "mtg#scryfall#scryfall-1#normal#LP", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 42, "in_stock", "import1");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#LP", 10, "in_stock", "import1");

    // act
    var response =
        updateUnitHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                Map.of("sku_id", "mtg#scryfall#scryfall-1#normal#NM", "sequence_number", "42"),
                "{\"condition\":\"LP\"}"),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("sku_id").asText()).isEqualTo("mtg#scryfall#scryfall-1#normal#LP");

    var sourceSkuPk = SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    var oldUnit =
        unitTable.getItem(
            Key.builder().partitionValue(sourceSkuPk).sortValue(UnitItem.formatSk(42)).build());
    assertThat(oldUnit).isNull();

    var targetSkuPk = SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#LP");
    var newUnit =
        unitTable.getItem(
            Key.builder().partitionValue(targetSkuPk).sortValue(UnitItem.formatSk(42)).build());
    assertThat(newUnit).isNotNull();
    assertThat(newUnit.getSequenceNumber()).isEqualTo(42);
    assertThat(newUnit.getGame()).isEqualTo("mtg");
    assertThat(newUnit.getStatus()).isEqualTo("in_stock");

    var sourceSku =
        skuTable.getItem(
            Key.builder().partitionValue(sourceSkuPk).sortValue(SkuItem.formatSk()).build());
    assertThat(sourceSku.getDirty()).isTrue();
    assertThat(sourceSku.getVersion()).isEqualTo(2);

    var targetSku =
        skuTable.getItem(
            Key.builder().partitionValue(targetSkuPk).sortValue(SkuItem.formatSk()).build());
    assertThat(targetSku.getDirty()).isTrue();
    assertThat(targetSku.getVersion()).isEqualTo(2);
    assertThat(targetSku.getSkuId()).isEqualTo("mtg#scryfall#scryfall-1#normal#LP");
    assertThat(targetSku.getGame()).isEqualTo("mtg");
    assertThat(targetSku.getExternalId()).isEqualTo("scryfall-1");

    var auditItems = queryAuditEntries("jordan");
    assertThat(auditItems).hasSize(1);
    assertThat(auditItems.get(0).getEventType()).isEqualTo("adjustment");
    assertThat(auditItems.get(0).getSkuId()).isEqualTo("mtg#scryfall#scryfall-1#normal#NM");
    assertThat(auditItems.get(0).getTargetSkuId()).isEqualTo("mtg#scryfall#scryfall-1#normal#LP");
    assertThat(auditItems.get(0).getSequenceNumber()).isEqualTo(42);
    assertThat(auditItems.get(0).getBeforeStatus()).isEqualTo("in_stock");
    assertThat(auditItems.get(0).getAfterStatus()).isEqualTo("in_stock");
  }

  @Test
  void updateUnitShouldReturn409WhenAJobIsActive() throws Exception {
    // arrange
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSku("jordan", skuId, "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", skuId, 42, "in_stock", "import1");
    jobTable.putItem(
        JobItem.create("jordan", "job1", "publish", null, null, Instant.ofEpochSecond(1700000000)));

    // act
    var response =
        updateUnitHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                Map.of("sku_id", skuId, "sequence_number", "42"),
                "{\"condition\":\"LP\"}"),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("another job is in progress");
    assertThat(getUnit("jordan", skuId, 42).getStatus()).isEqualTo("in_stock");
    assertThat(queryAuditEntries("jordan")).isEmpty();
  }

  @Test
  void updateUnitShouldRollBackAllWritesWhenTargetUnitKeyIsOccupied() throws Exception {
    // arrange
    var sourceSkuId = "mtg#scryfall#scryfall-1#normal#NM";
    var targetSkuId = "mtg#scryfall#scryfall-1#normal#LP";
    createSku("jordan", sourceSkuId, "Elvish Mystic", "m14", "Magic 2014", "169");
    createSku("jordan", targetSkuId, "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", sourceSkuId, 42, "in_stock", "import1");
    createUnit("jordan", targetSkuId, 42, "reserved", "import2");

    // act
    assertThatThrownBy(
            () ->
                updateUnitHandler.handleRequest(
                    buildEventWithBody(
                        "jordan",
                        Map.of("sku_id", sourceSkuId, "sequence_number", "42"),
                        "{\"condition\":\"LP\"}"),
                    null))
        .isInstanceOf(RuntimeException.class);

    // assert
    assertThat(getUnit("jordan", sourceSkuId, 42).getStatus()).isEqualTo("in_stock");
    assertThat(getUnit("jordan", targetSkuId, 42).getStatus()).isEqualTo("reserved");
    assertThat(getSku("jordan", sourceSkuId).getDirty()).isFalse();
    assertThat(getSku("jordan", sourceSkuId).getVersion()).isEqualTo(1);
    assertThat(getSku("jordan", targetSkuId).getDirty()).isFalse();
    assertThat(getSku("jordan", targetSkuId).getVersion()).isEqualTo(1);
    assertThat(queryAuditEntries("jordan")).isEmpty();
  }

  @Test
  void updateUnitShouldCarryPhotosAcrossSkuPartitions() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createSku(
        "jordan", "mtg#scryfall#scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    var photos =
        List.of(
            UnitItem.Photo.create("photo-front", null),
            UnitItem.Photo.create(
                "photo-back", "https://listing-img.fetchtcg.com/example/listing/photo.jpg"));
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 42, "in_stock", "import1", photos);

    // act
    var response =
        updateUnitHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                Map.of("sku_id", "mtg#scryfall#scryfall-1#normal#NM", "sequence_number", "42"),
                "{\"condition\":\"LP\"}"),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);

    var sourceSkuPk = SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    var oldUnit =
        unitTable.getItem(
            Key.builder().partitionValue(sourceSkuPk).sortValue(UnitItem.formatSk(42)).build());
    assertThat(oldUnit).isNull();

    var targetSkuPk = SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#LP");
    var newUnit =
        unitTable.getItem(
            Key.builder().partitionValue(targetSkuPk).sortValue(UnitItem.formatSk(42)).build());
    assertThat(newUnit).isNotNull();
    assertThat(newUnit.getPhotos()).isEqualTo(photos);
  }

  @Test
  void getSkuShouldReturnUnitPhotosWithPresignedUrls() throws Exception {
    // arrange
    createSku(
        "jordan", "mtg#scryfall#scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit(
        "jordan",
        "mtg#scryfall#scryfall-1#normal#NM",
        4242,
        "in_stock",
        "import1",
        List.of(UnitItem.Photo.create("photo-in-stock", null)));
    createUnit(
        "jordan",
        "mtg#scryfall#scryfall-1#normal#NM",
        1204,
        "reserved",
        "import1",
        List.of(UnitItem.Photo.create("photo-reserved", null)));
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 4250, "in_stock", "import1");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 300, "removed", "import1");

    // act
    var response =
        getSkuHandler.handleRequest(
            buildEvent("jordan", Map.of("sku_id", "mtg#scryfall#scryfall-1#normal#NM")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var units = objectMapper.readTree(response.getBody()).get("units");
    assertThat(units).hasSize(3);

    assertThat(units.get(0).get("sequence_number").asInt()).isEqualTo(1204);
    assertThat(units.get(0).get("status").asText()).isEqualTo("reserved");
    assertThat(units.get(0).get("photos")).hasSize(1);
    assertThat(units.get(0).get("photos").get(0).get("photo_id").asText())
        .isEqualTo("photo-reserved");
    assertThat(units.get(0).get("photos").get(0).get("url").asText())
        .contains(Photos.key("jordan", "photo-reserved"));

    assertThat(units.get(1).get("sequence_number").asInt()).isEqualTo(4242);
    assertThat(units.get(1).get("photos")).hasSize(1);
    assertThat(units.get(1).get("photos").get(0).get("photo_id").asText())
        .isEqualTo("photo-in-stock");
    assertThat(units.get(1).get("photos").get(0).get("url").asText())
        .contains(Photos.key("jordan", "photo-in-stock"));

    assertThat(units.get(2).get("sequence_number").asInt()).isEqualTo(4250);
    assertThat(units.get(2).get("photos")).isEmpty();
  }

  @Test
  void updateUnitShouldCreateTargetSkuWhenNotExists() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createSku(
        "jordan", "mtg#scryfall#scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 42, "in_stock", "import1");

    // act
    var response =
        updateUnitHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                Map.of("sku_id", "mtg#scryfall#scryfall-1#normal#NM", "sequence_number", "42"),
                "{\"condition\":\"LP\"}"),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("sku_id").asText()).isEqualTo("mtg#scryfall#scryfall-1#normal#LP");

    var targetSkuPk = SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#LP");
    var targetSku =
        skuTable.getItem(
            Key.builder().partitionValue(targetSkuPk).sortValue(SkuItem.formatSk()).build());
    assertThat(targetSku).isNotNull();
    assertThat(targetSku.getSkuId()).isEqualTo("mtg#scryfall#scryfall-1#normal#LP");
    assertThat(targetSku.getName()).isEqualTo("Elvish Mystic");
    assertThat(targetSku.getCondition()).isEqualTo("LP");
    assertThat(targetSku.getFinish()).isEqualTo("normal");
    assertThat(targetSku.getGsi2pk()).isEqualTo(SkuItem.formatGsi2pk("jordan"));
    assertThat(targetSku.getGsi2sk())
        .isEqualTo(
            SkuItem.formatGsi2sk("mtg", "elvish mystic", "mtg#scryfall#scryfall-1#normal#LP"));
  }

  @Test
  void updateUnitShouldReturn409ForNonInStockUnit() {
    // arrange
    createSku(
        "jordan", "mtg#scryfall#scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 42, "reserved", "import1");

    // act
    var response =
        updateUnitHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                Map.of("sku_id", "mtg#scryfall#scryfall-1#normal#NM", "sequence_number", "42"),
                "{\"condition\":\"LP\"}"),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
  }

  @Test
  void updateUnitShouldReturn409ForSameCondition() {
    // arrange
    createSku(
        "jordan", "mtg#scryfall#scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 42, "in_stock", "import1");

    // act
    var response =
        updateUnitHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                Map.of("sku_id", "mtg#scryfall#scryfall-1#normal#NM", "sequence_number", "42"),
                "{\"condition\":\"NM\"}"),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
  }

  @Test
  void zeroCountSkuShouldPersistAfterRemoval() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createSku(
        "jordan", "mtg#scryfall#scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "mtg#scryfall#scryfall-1#normal#NM", 42, "in_stock", "import1");

    // act
    removeUnitHandler.handleRequest(
        buildEvent(
            "jordan",
            Map.of("sku_id", "mtg#scryfall#scryfall-1#normal#NM", "sequence_number", "42")),
        null);

    // assert
    var skuPk = SkuItem.formatPk("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    var sku =
        skuTable.getItem(Key.builder().partitionValue(skuPk).sortValue(SkuItem.formatSk()).build());
    assertThat(sku).isNotNull();
    assertThat(sku.getSkuId()).isEqualTo("mtg#scryfall#scryfall-1#normal#NM");

    var getResponse =
        getSkuHandler.handleRequest(
            buildEvent("jordan", Map.of("sku_id", "mtg#scryfall#scryfall-1#normal#NM")), null);
    var body = objectMapper.readTree(getResponse.getBody());
    assertThat(body.get("in_stock_count").asInt()).isEqualTo(0);
    assertThat(body.get("units")).isEmpty();
  }

  private void createSku(
      String user,
      String skuId,
      String name,
      String setCode,
      String setName,
      String collectorNumber) {
    var parts = skuId.split("#");
    var externalId = parts[2];
    var finish = parts[3];
    var condition = parts[4];

    var item =
        SkuItem.create(
            user,
            skuId,
            parts[0],
            externalId,
            finish,
            condition,
            name,
            setCode,
            setName,
            collectorNumber,
            null,
            null);
    item.setDirty(false);
    item.setGsi1pk(SkuItem.USER_PREFIX + user + "#CLEAN");
    skuTable.putItem(item);
  }

  private void createUnit(
      String user, String skuId, int sequenceNumber, String status, String importId) {
    createUnit(user, skuId, sequenceNumber, status, importId, null);
  }

  private void createUnit(
      String user,
      String skuId,
      int sequenceNumber,
      String status,
      String importId,
      List<UnitItem.Photo> photos) {
    var item =
        UnitItem.create(
            user,
            "mtg",
            skuId,
            sequenceNumber,
            status,
            importId,
            Instant.ofEpochSecond(1700000000));
    if (photos != null) {
      item.setPhotos(photos);
    }
    unitTable.putItem(item);
  }

  private SkuItem getSku(String user, String skuId) {
    return skuTable.getItem(
        Key.builder()
            .partitionValue(SkuItem.formatPk(user, skuId))
            .sortValue(SkuItem.formatSk())
            .build());
  }

  private UnitItem getUnit(String user, String skuId, int sequenceNumber) {
    return unitTable.getItem(
        Key.builder()
            .partitionValue(SkuItem.formatPk(user, skuId))
            .sortValue(UnitItem.formatSk(sequenceNumber))
            .build());
  }

  private List<AuditItem> queryAuditEntries(String user) {
    var queryConditional =
        QueryConditional.keyEqualTo(Key.builder().partitionValue(AuditItem.formatPk(user)).build());
    return auditTable
        .query(QueryEnhancedRequest.builder().queryConditional(queryConditional).build())
        .stream()
        .flatMap(page -> page.items().stream())
        .toList();
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

  private APIGatewayV2HTTPEvent buildEventWithQuery(
      String user, Map<String, String> pathParams, Map<String, String> queryParams) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withPathParameters(pathParams)
        .withQueryStringParameters(queryParams)
        .build();
  }

  private APIGatewayV2HTTPEvent buildEventWithBody(
      String user, Map<String, String> pathParams, String body) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withPathParameters(pathParams)
        .withBody(body)
        .build();
  }
}
