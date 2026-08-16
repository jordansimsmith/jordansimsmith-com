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
public class InventoryHandlerIntegrationTest {

  private FakeClock fakeClock;
  private FakeUlidGenerator fakeUlidGenerator;
  private ObjectMapper objectMapper;
  private DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

  private FindSkusHandler findSkusHandler;
  private GetSkuHandler getSkuHandler;
  private RemoveUnitHandler removeUnitHandler;
  private UpdateUnitHandler updateUnitHandler;

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

    findSkusHandler = new FindSkusHandler(factory);
    getSkuHandler = new GetSkuHandler(factory);
    removeUnitHandler = new RemoveUnitHandler(factory);
    updateUnitHandler = new UpdateUnitHandler(factory);
  }

  @Test
  void findSkusShouldReturnSkusAlphabetically() throws Exception {
    // arrange
    createSku("jordan", "scryfall-z#normal#NM", "Zombie Knight", "dom", "Dominaria", "100");
    createSku("jordan", "scryfall-a#normal#NM", "Angel of Mercy", "dom", "Dominaria", "101");
    createSku("jordan", "scryfall-e#foil#LP", "Elvish Mystic", "m14", "Magic 2014", "169");

    // act
    var response = findSkusHandler.handleRequest(buildEvent("jordan", Map.of()), null);

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
    assertThat(body.get("next_continuation").isNull()).isTrue();
  }

  @Test
  void findSkusShouldSupportPrefixSearch() throws Exception {
    // arrange
    createSku("jordan", "scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createSku("jordan", "scryfall-2#normal#NM", "Elvish Aberration", "a25", "Masters 25", "167");
    createSku("jordan", "scryfall-3#normal#NM", "Sol Ring", "c21", "Commander 2021", "167");

    // act
    var response =
        findSkusHandler.handleRequest(
            buildEventWithQuery("jordan", Map.of(), Map.of("search", "elv")), null);

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
    createSku("jordan", "scryfall-1#normal#NM", "Alpha Card", "dom", "Dominaria", "1");
    createSku("jordan", "scryfall-2#normal#NM", "Beta Card", "dom", "Dominaria", "2");
    createSku("jordan", "scryfall-3#normal#NM", "Gamma Card", "dom", "Dominaria", "3");

    // act - first page
    var response1 =
        findSkusHandler.handleRequest(
            buildEventWithQuery("jordan", Map.of(), Map.of("limit", "2")), null);

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
            buildEventWithQuery("jordan", Map.of(), Map.of("continuation", continuation)), null);

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
    createSku("jordan", "scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "scryfall-1#normal#NM", 4242, "in_stock", "import1");
    createUnit("jordan", "scryfall-1#normal#NM", 1204, "reserved", "import1");
    createUnit("jordan", "scryfall-1#normal#NM", 4250, "in_stock", "import1");
    createUnit("jordan", "scryfall-1#normal#NM", 500, "sold", "import1");
    createUnit("jordan", "scryfall-1#normal#NM", 300, "removed", "import1");

    // act
    var response =
        getSkuHandler.handleRequest(
            buildEvent("jordan", Map.of("sku_id", "scryfall-1#normal#NM")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("sku_id").asText()).isEqualTo("scryfall-1#normal#NM");
    assertThat(body.get("scryfall_id").asText()).isEqualTo("scryfall-1");
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
    createSku("jordan", "scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "scryfall-1#normal#NM", 4242, "in_stock", "import1");

    // act
    var response =
        getSkuHandler.handleRequest(
            buildEvent("jordan", Map.of("sku_id", "scryfall-1%23normal%23NM")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("sku_id").asText()).isEqualTo("scryfall-1#normal#NM");
  }

  @Test
  void getSkuShouldReturn404ForUnknownSku() {
    // act
    var response =
        getSkuHandler.handleRequest(
            buildEvent("jordan", Map.of("sku_id", "nonexistent#normal#NM")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void removeUnitShouldSetStatusToRemovedAndDirtySku() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createSku("jordan", "scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "scryfall-1#normal#NM", 42, "in_stock", "import1");
    createUnit("jordan", "scryfall-1#normal#NM", 43, "in_stock", "import1");

    // act
    var response =
        removeUnitHandler.handleRequest(
            buildEventWithQuery(
                "jordan",
                Map.of("sku_id", "scryfall-1#normal#NM", "sequence_number", "42"),
                Map.of("reason", "damaged")),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);

    var skuPk = TcgInventoryItem.formatSkuPk("jordan", "scryfall-1#normal#NM");
    var sku =
        tcgInventoryTable.getItem(
            Key.builder().partitionValue(skuPk).sortValue(TcgInventoryItem.formatSkuSk()).build());
    assertThat(sku.getDirty()).isTrue();
    assertThat(sku.getVersion()).isEqualTo(2);
    assertThat(sku.getGsi1pk()).isEqualTo(TcgInventoryItem.formatGsi1pk("jordan"));

    var unit =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(skuPk)
                .sortValue(TcgInventoryItem.formatUnitSk(42))
                .build());
    assertThat(unit.getStatus()).isEqualTo("removed");

    var auditItems = queryAuditEntries("jordan");
    assertThat(auditItems).hasSize(1);
    assertThat(auditItems.get(0).getEventType()).isEqualTo("adjustment");
    assertThat(auditItems.get(0).getSkuId()).isEqualTo("scryfall-1#normal#NM");
  }

  @Test
  void removeUnitShouldReturn404ForMissingUnit() {
    // arrange
    createSku("jordan", "scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");

    // act
    var response =
        removeUnitHandler.handleRequest(
            buildEvent("jordan", Map.of("sku_id", "scryfall-1#normal#NM", "sequence_number", "99")),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void removeUnitShouldReturn409ForNonInStockUnit() {
    // arrange
    createSku("jordan", "scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "scryfall-1#normal#NM", 42, "reserved", "import1");

    // act
    var response =
        removeUnitHandler.handleRequest(
            buildEvent("jordan", Map.of("sku_id", "scryfall-1#normal#NM", "sequence_number", "42")),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
  }

  @Test
  void updateUnitShouldMoveUnitBetweenSkus() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createSku("jordan", "scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createSku("jordan", "scryfall-1#normal#LP", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "scryfall-1#normal#NM", 42, "in_stock", "import1");
    createUnit("jordan", "scryfall-1#normal#LP", 10, "in_stock", "import1");

    // act
    var response =
        updateUnitHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                Map.of("sku_id", "scryfall-1#normal#NM", "sequence_number", "42"),
                "{\"condition\":\"LP\"}"),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("sku_id").asText()).isEqualTo("scryfall-1#normal#LP");

    var sourceSkuPk = TcgInventoryItem.formatSkuPk("jordan", "scryfall-1#normal#NM");
    var oldUnit =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(sourceSkuPk)
                .sortValue(TcgInventoryItem.formatUnitSk(42))
                .build());
    assertThat(oldUnit).isNull();

    var targetSkuPk = TcgInventoryItem.formatSkuPk("jordan", "scryfall-1#normal#LP");
    var newUnit =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(targetSkuPk)
                .sortValue(TcgInventoryItem.formatUnitSk(42))
                .build());
    assertThat(newUnit).isNotNull();
    assertThat(newUnit.getSequenceNumber()).isEqualTo(42);
    assertThat(newUnit.getStatus()).isEqualTo("in_stock");

    var sourceSku =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(sourceSkuPk)
                .sortValue(TcgInventoryItem.formatSkuSk())
                .build());
    assertThat(sourceSku.getDirty()).isTrue();
    assertThat(sourceSku.getVersion()).isEqualTo(2);

    var targetSku =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(targetSkuPk)
                .sortValue(TcgInventoryItem.formatSkuSk())
                .build());
    assertThat(targetSku.getDirty()).isTrue();
    assertThat(targetSku.getVersion()).isEqualTo(2);

    var auditItems = queryAuditEntries("jordan");
    assertThat(auditItems).hasSize(1);
    assertThat(auditItems.get(0).getEventType()).isEqualTo("adjustment");
  }

  @Test
  void updateUnitShouldCreateTargetSkuWhenNotExists() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createSku("jordan", "scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "scryfall-1#normal#NM", 42, "in_stock", "import1");

    // act
    var response =
        updateUnitHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                Map.of("sku_id", "scryfall-1#normal#NM", "sequence_number", "42"),
                "{\"condition\":\"LP\"}"),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("sku_id").asText()).isEqualTo("scryfall-1#normal#LP");

    var targetSkuPk = TcgInventoryItem.formatSkuPk("jordan", "scryfall-1#normal#LP");
    var targetSku =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(targetSkuPk)
                .sortValue(TcgInventoryItem.formatSkuSk())
                .build());
    assertThat(targetSku).isNotNull();
    assertThat(targetSku.getSkuId()).isEqualTo("scryfall-1#normal#LP");
    assertThat(targetSku.getName()).isEqualTo("Elvish Mystic");
    assertThat(targetSku.getCondition()).isEqualTo("LP");
    assertThat(targetSku.getFinish()).isEqualTo("normal");
    assertThat(targetSku.getGsi2pk()).isEqualTo(TcgInventoryItem.formatGsi2pk("jordan"));
    assertThat(targetSku.getGsi2sk())
        .isEqualTo(TcgInventoryItem.formatGsi2sk("elvish mystic", "scryfall-1#normal#LP"));
  }

  @Test
  void updateUnitShouldReturn409ForNonInStockUnit() {
    // arrange
    createSku("jordan", "scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "scryfall-1#normal#NM", 42, "reserved", "import1");

    // act
    var response =
        updateUnitHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                Map.of("sku_id", "scryfall-1#normal#NM", "sequence_number", "42"),
                "{\"condition\":\"LP\"}"),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
  }

  @Test
  void updateUnitShouldReturn409ForSameCondition() {
    // arrange
    createSku("jordan", "scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "scryfall-1#normal#NM", 42, "in_stock", "import1");

    // act
    var response =
        updateUnitHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                Map.of("sku_id", "scryfall-1#normal#NM", "sequence_number", "42"),
                "{\"condition\":\"NM\"}"),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
  }

  @Test
  void zeroCountSkuShouldPersistAfterRemoval() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createSku("jordan", "scryfall-1#normal#NM", "Elvish Mystic", "m14", "Magic 2014", "169");
    createUnit("jordan", "scryfall-1#normal#NM", 42, "in_stock", "import1");

    // act
    removeUnitHandler.handleRequest(
        buildEvent("jordan", Map.of("sku_id", "scryfall-1#normal#NM", "sequence_number", "42")),
        null);

    // assert
    var skuPk = TcgInventoryItem.formatSkuPk("jordan", "scryfall-1#normal#NM");
    var sku =
        tcgInventoryTable.getItem(
            Key.builder().partitionValue(skuPk).sortValue(TcgInventoryItem.formatSkuSk()).build());
    assertThat(sku).isNotNull();
    assertThat(sku.getSkuId()).isEqualTo("scryfall-1#normal#NM");

    var getResponse =
        getSkuHandler.handleRequest(
            buildEvent("jordan", Map.of("sku_id", "scryfall-1#normal#NM")), null);
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
    var scryfallId = parts[0];
    var finish = parts[1];
    var condition = parts[2];

    var item =
        TcgInventoryItem.createSku(
            user,
            skuId,
            scryfallId,
            finish,
            condition,
            name,
            setCode,
            setName,
            collectorNumber,
            null,
            null);
    item.setDirty(false);
    item.setGsi1pk(TcgInventoryItem.USER_PREFIX + user + "#CLEAN");
    tcgInventoryTable.putItem(item);
  }

  private void createUnit(
      String user, String skuId, int sequenceNumber, String status, String importId) {
    var item =
        TcgInventoryItem.createUnit(
            user, skuId, sequenceNumber, status, importId, Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(item);
  }

  private List<TcgInventoryItem> queryAuditEntries(String user) {
    var queryConditional =
        QueryConditional.keyEqualTo(
            Key.builder().partitionValue(TcgInventoryItem.formatAuditPk(user)).build());
    return tcgInventoryTable
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
