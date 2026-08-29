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
import java.util.ArrayList;
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
public class OrdersHandlerIntegrationTest {

  private FakeClock fakeClock;
  private FakeUlidGenerator fakeUlidGenerator;
  private ObjectMapper objectMapper;
  private DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

  private FindOrdersHandler findOrdersHandler;
  private GetOrderHandler getOrderHandler;
  private ConfirmOrderHandler confirmOrderHandler;

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

    findOrdersHandler = new FindOrdersHandler(factory);
    getOrderHandler = new GetOrderHandler(factory);
    confirmOrderHandler = new ConfirmOrderHandler(factory);
  }

  @Test
  void findOrdersShouldReturnEmptyWhenNoOrders() throws Exception {
    // act
    var response = findOrdersHandler.handleRequest(buildEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("orders")).isEmpty();
  }

  @Test
  void findOrdersShouldReturnOrdersNewestFirst() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createOrder("jordan", "10001", "awaiting_payment", "PICKUP", "2.00");
    createOrder("jordan", "10002", "to_pick", "SHIPPING", "5.50");

    // act
    var response = findOrdersHandler.handleRequest(buildEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var orders = body.get("orders");
    assertThat(orders).hasSize(2);
    assertThat(orders.get(0).get("order_id").asText()).isEqualTo("10002");
    assertThat(orders.get(0).get("state").asText()).isEqualTo("to_pick");
    assertThat(orders.get(0).get("delivery_mode").asText()).isEqualTo("SHIPPING");
    assertThat(orders.get(0).get("total_price").asText()).isEqualTo("5.50");
    assertThat(orders.get(0).get("accepted_at").asLong()).isEqualTo(1700000000);
    assertThat(orders.get(0).get("items_total_price").isNull()).isTrue();
    assertThat(orders.get(0).get("listed_total_price").isNull()).isTrue();
    assertThat(orders.get(1).get("order_id").asText()).isEqualTo("10001");
    assertThat(orders.get(1).get("state").asText()).isEqualTo("awaiting_payment");
    assertThat(body.get("next_continuation").isNull()).isTrue();
  }

  @Test
  void findOrdersShouldSupportContinuationPaging() throws Exception {
    // arrange
    createOrder("jordan", "10001", "awaiting_payment", "PICKUP", "2.00");
    createOrder("jordan", "10002", "to_pick", "SHIPPING", "5.50");
    createOrder("jordan", "10003", "fulfilled", "PICKUP", "1.00");

    // act - first page
    var response1 =
        findOrdersHandler.handleRequest(
            buildEventWithQuery("jordan", Map.of(), Map.of("limit", "2")), null);

    // assert - first page
    assertThat(response1.getStatusCode()).isEqualTo(200);
    var body1 = objectMapper.readTree(response1.getBody());
    assertThat(body1.get("orders")).hasSize(2);
    assertThat(body1.get("orders").get(0).get("order_id").asText()).isEqualTo("10003");
    assertThat(body1.get("orders").get(1).get("order_id").asText()).isEqualTo("10002");
    var continuation = body1.get("next_continuation").asText();
    assertThat(continuation).isNotNull();
    assertThat(continuation).isNotEmpty();

    // act - second page
    var response2 =
        findOrdersHandler.handleRequest(
            buildEventWithQuery("jordan", Map.of(), Map.of("continuation", continuation)), null);

    // assert - second page
    assertThat(response2.getStatusCode()).isEqualTo(200);
    var body2 = objectMapper.readTree(response2.getBody());
    assertThat(body2.get("orders")).hasSize(1);
    assertThat(body2.get("orders").get(0).get("order_id").asText()).isEqualTo("10001");
    assertThat(body2.get("next_continuation").isNull()).isTrue();
  }

  @Test
  void findOrdersShouldReturnItemAndListedTotals() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 2);
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1, 2), "PICKUP", "3.00", "2.00");

    // act
    var response = findOrdersHandler.handleRequest(buildEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var order = body.get("orders").get(0);
    assertThat(order.get("total_price").asText()).isEqualTo("3.00");
    assertThat(order.get("items_total_price").asText()).isEqualTo("1.50");
    assertThat(order.get("listed_total_price").asText()).isEqualTo("4.00");
    assertThat(order.get("unit_count").asInt()).isEqualTo(2);
  }

  @Test
  void findOrdersShouldOmitListedTotalWhenBaselineMissing() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 1);
    createOrderWithLines("jordan", "83663", "to_pick", skuId, List.of(1), "PICKUP", "1.50", null);

    // act
    var response = findOrdersHandler.handleRequest(buildEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var order = body.get("orders").get(0);
    assertThat(order.get("items_total_price").asText()).isEqualTo("1.50");
    assertThat(order.get("listed_total_price").isNull()).isTrue();
  }

  @Test
  void getOrderShouldReturnDetailWithUnitsAndLocations() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 3);
    reserveUnits("jordan", skuId, "83663", List.of(1, 3));
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1, 3), "PICKUP", "3.33", "3.50");

    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("order_id").asText()).isEqualTo("83663");
    assertThat(body.get("state").asText()).isEqualTo("to_pick");
    assertThat(body.get("accepted_at").asLong()).isEqualTo(1700000000);
    assertThat(body.get("delivery_mode").asText()).isEqualTo("PICKUP");
    assertThat(body.get("total_price").asText()).isEqualTo("3.33");
    assertThat(body.get("unit_count").asInt()).isEqualTo(2);

    var lines = body.get("lines");
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).get("name").asText()).isEqualTo("Test Card");
    assertThat(lines.get(0).get("set_code").asText()).isEqualTo("dom");
    assertThat(lines.get(0).get("collector_number").asText()).isEqualTo("168");
    assertThat(lines.get(0).get("finish").asText()).isEqualTo("normal");
    assertThat(lines.get(0).get("condition").asText()).isEqualTo("NM");
    assertThat(lines.get(0).get("quantity").asInt()).isEqualTo(2);
    assertThat(lines.get(0).get("price").asText()).isEqualTo("1.50");
    assertThat(lines.get(0).get("listed_price").asText()).isEqualTo("3.50");

    var units = body.get("units");
    assertThat(units).hasSize(2);
    assertThat(units.get(0).get("sequence_number").asInt()).isEqualTo(1);
    assertThat(units.get(0).get("location").asText()).isEqualTo("A0-1");
    assertThat(units.get(0).get("name").asText()).isEqualTo("Test Card");
    assertThat(units.get(0).get("set_code").asText()).isEqualTo("dom");
    assertThat(units.get(0).get("collector_number").asText()).isEqualTo("168");
    assertThat(units.get(0).get("finish").asText()).isEqualTo("normal");
    assertThat(units.get(0).get("condition").asText()).isEqualTo("NM");
    assertThat(units.get(1).get("sequence_number").asInt()).isEqualTo(3);
    assertThat(units.get(1).get("location").asText()).isEqualTo("A0-3");
  }

  @Test
  void getOrderShouldReturnUnitsFromMultipleSkus() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId1 = "scryfall-1#normal#NM";
    var skuId2 = "scryfall-2#foil#LP";
    createSkuWithUnitsNamed("jordan", skuId1, 2, "Lightning Bolt", "dom", "168");
    createSkuWithUnitsNamed("jordan", skuId2, 2, "Sol Ring", "c21", "100");
    reserveUnits("jordan", skuId1, "83663", List.of(1));
    reserveUnits("jordan", skuId2, "83663", List.of(1));

    var lines =
        "[{\"sku_id\":\""
            + skuId1
            + "\",\"fetchtcg_listing_id\":1001,\"quantity\":1,\"price\":\"1.50\""
            + ",\"listed_price\":\"2.00\",\"allocated_sequence_numbers\":[1]},"
            + "{\"sku_id\":\""
            + skuId2
            + "\",\"fetchtcg_listing_id\":1002,\"quantity\":1,\"price\":\"2.00\""
            + ",\"listed_price\":\"1.80\",\"allocated_sequence_numbers\":[1]}]";
    var order =
        TcgInventoryItem.createOrder(
            "jordan",
            "83663",
            "to_pick",
            "ACCEPTED",
            "SEND_PICKUP_ADDRESS",
            "PICKUP",
            "3.50",
            lines,
            Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(order);

    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var units = body.get("units");
    assertThat(units).hasSize(2);
    assertThat(units.get(0).get("name").asText()).isIn("Lightning Bolt", "Sol Ring");
    assertThat(units.get(1).get("name").asText()).isIn("Lightning Bolt", "Sol Ring");

    var responseLines = body.get("lines");
    assertThat(responseLines).hasSize(2);
    assertThat(responseLines.get(0).get("name").asText()).isEqualTo("Lightning Bolt");
    assertThat(responseLines.get(0).get("price").asText()).isEqualTo("1.50");
    assertThat(responseLines.get(0).get("listed_price").asText()).isEqualTo("2.00");
    assertThat(responseLines.get(1).get("name").asText()).isEqualTo("Sol Ring");
    assertThat(responseLines.get(1).get("listed_price").asText()).isEqualTo("1.80");
  }

  @Test
  void getOrderShouldReturnNullListedPriceForLegacyOrders() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 1);
    reserveUnits("jordan", skuId, "83663", List.of(1));
    createOrderWithLines("jordan", "83663", "to_pick", skuId, List.of(1), "PICKUP", "1.50", null);

    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var lines = body.get("lines");
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).get("price").asText()).isEqualTo("1.50");
    assertThat(lines.get(0).get("listed_price").isNull()).isTrue();
  }

  @Test
  void getOrderShouldReturn404ForUnknownOrder() {
    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "nonexistent")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void confirmOrderShouldMarkUnitsAsSold() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 3);
    reserveUnits("jordan", skuId, "83663", List.of(1, 2));
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1, 2), "PICKUP", "3.00", null);

    // act
    var response =
        confirmOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("order_id").asText()).isEqualTo("83663");
    assertThat(body.get("state").asText()).isEqualTo("fulfilled");

    var order = getOrderItem("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("fulfilled");

    var units = getUnits("jordan", skuId);
    var soldUnits = units.stream().filter(u -> "sold".equals(u.getStatus())).toList();
    assertThat(soldUnits).hasSize(2);
    var inStockUnits = units.stream().filter(u -> "in_stock".equals(u.getStatus())).toList();
    assertThat(inStockUnits).hasSize(1);

    var sku = getSkuItem("jordan", skuId);
    assertThat(sku.getVersion()).isEqualTo(2);

    var audit = getAuditEntries("jordan");
    assertThat(audit.stream().anyMatch(a -> "sell".equals(a.getEventType()))).isTrue();
    assertThat(
            audit.stream()
                .filter(a -> "sell".equals(a.getEventType()))
                .anyMatch(a -> "83663".equals(a.getOrderId())))
        .isTrue();
  }

  @Test
  void confirmOrderShouldSellLargeOrderAcrossTransactions() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var lines = new ArrayList<OrderLines.OrderLine>();
    for (int i = 1; i <= 60; i++) {
      var skuId = "scryfall-" + i + "#normal#NM";
      createSkuWithUnits("jordan", skuId, 1);
      reserveUnits("jordan", skuId, "83663", List.of(1));
      lines.add(new OrderLines.OrderLine(skuId, 1000 + i, 1, "0.50", "0.50", List.of(1)));
    }
    var order =
        TcgInventoryItem.createOrder(
            "jordan",
            "83663",
            "to_pick",
            "ACCEPTED",
            "SEND_PICKUP_ADDRESS",
            "PICKUP",
            "30.00",
            objectMapper.writeValueAsString(lines),
            Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(order);

    // act
    var response =
        confirmOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var updatedOrder = getOrderItem("jordan", "83663");
    assertThat(updatedOrder.getStatus()).isEqualTo("fulfilled");

    for (int i = 1; i <= 60; i++) {
      var units = getUnits("jordan", "scryfall-" + i + "#normal#NM");
      assertThat(units).hasSize(1);
      assertThat(units.get(0).getStatus()).isEqualTo("sold");
    }

    var sellAudits =
        getAuditEntries("jordan").stream().filter(a -> "sell".equals(a.getEventType())).toList();
    assertThat(sellAudits).hasSize(1);
  }

  @Test
  void confirmOrderShouldNotSetDirtyFlag() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 2);

    var sku = getSkuItem("jordan", skuId);
    sku.setDirty(false);
    sku.setGsi1pk(TcgInventoryItem.USER_PREFIX + "jordan" + "#CLEAN");
    tcgInventoryTable.putItem(sku);

    reserveUnits("jordan", skuId, "83663", List.of(1));
    createOrderWithLines("jordan", "83663", "to_pick", skuId, List.of(1), "PICKUP", "1.50", null);

    // act
    confirmOrderHandler.handleRequest(
        buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    var updatedSku = getSkuItem("jordan", skuId);
    assertThat(updatedSku.getDirty()).isFalse();
    assertThat(updatedSku.getGsi1pk())
        .isEqualTo(TcgInventoryItem.USER_PREFIX + "jordan" + "#CLEAN");
  }

  @Test
  void confirmOrderShouldReturn409WhenNotToPick() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createOrder("jordan", "83663", "awaiting_payment", "PICKUP", "3.00");

    // act
    var response =
        confirmOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("message").asText()).isEqualTo("order is not ready to pick");
  }

  @Test
  void confirmOrderShouldReturn404ForUnknownOrder() {
    // act
    var response =
        confirmOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "nonexistent")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  private void createOrder(
      String user, String offerId, String status, String deliveryMode, String totalPrice) {
    var order =
        TcgInventoryItem.createOrder(
            user,
            offerId,
            status,
            "ACCEPTED",
            null,
            deliveryMode,
            totalPrice,
            "[]",
            Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(order);
  }

  private void createSkuWithUnits(String user, String skuId, int unitCount) {
    createSkuWithUnitsNamed(user, skuId, unitCount, "Test Card", "dom", "168");
  }

  private void createSkuWithUnitsNamed(
      String user,
      String skuId,
      int unitCount,
      String name,
      String setCode,
      String collectorNumber) {
    var parts = skuId.split("#");
    var skuItem =
        TcgInventoryItem.createSku(
            user,
            skuId,
            parts[0],
            parts[1],
            parts[2],
            name,
            setCode,
            "Test Set",
            collectorNumber,
            null,
            null);
    tcgInventoryTable.putItem(skuItem);

    for (int i = 1; i <= unitCount; i++) {
      var unit =
          TcgInventoryItem.createUnit(
              user, skuId, i, "in_stock", "import1", Instant.ofEpochSecond(1700000000));
      tcgInventoryTable.putItem(unit);
    }
  }

  private void reserveUnits(
      String user, String skuId, String orderId, List<Integer> sequenceNumbers) {
    for (var seqNum : sequenceNumbers) {
      var unit =
          tcgInventoryTable.getItem(
              Key.builder()
                  .partitionValue(TcgInventoryItem.formatSkuPk(user, skuId))
                  .sortValue(TcgInventoryItem.formatUnitSk(seqNum))
                  .build());
      unit.setStatus("reserved");
      unit.setOrderId(orderId);
      tcgInventoryTable.putItem(unit);
    }
  }

  private void createOrderWithLines(
      String user,
      String offerId,
      String status,
      String skuId,
      List<Integer> sequenceNumbers,
      String deliveryMode,
      String totalPrice,
      String listedPrice) {
    var listedPriceJson = listedPrice != null ? ",\"listed_price\":\"" + listedPrice + "\"" : "";
    var lines =
        "[{\"sku_id\":\""
            + skuId
            + "\",\"fetchtcg_listing_id\":1001,\"quantity\":"
            + sequenceNumbers.size()
            + ",\"price\":\"1.50\""
            + listedPriceJson
            + ",\"allocated_sequence_numbers\":"
            + sequenceNumbers
            + "}]";
    var order =
        TcgInventoryItem.createOrder(
            user,
            offerId,
            status,
            "ACCEPTED",
            "SEND_PICKUP_ADDRESS",
            deliveryMode,
            totalPrice,
            lines,
            Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(order);
  }

  private TcgInventoryItem getOrderItem(String user, String offerId) {
    return tcgInventoryTable.getItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatOrderSk(offerId))
            .build());
  }

  private TcgInventoryItem getSkuItem(String user, String skuId) {
    return tcgInventoryTable.getItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatSkuPk(user, skuId))
            .sortValue(TcgInventoryItem.formatSkuSk())
            .build());
  }

  private List<TcgInventoryItem> getUnits(String user, String skuId) {
    var results = new ArrayList<TcgInventoryItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(TcgInventoryItem.formatSkuPk(user, skuId))
                        .sortValue(TcgInventoryItem.UNIT_PREFIX)
                        .build()))
            .build();
    tcgInventoryTable.query(request).items().forEach(results::add);
    return results;
  }

  private List<TcgInventoryItem> getAuditEntries(String user) {
    var results = new ArrayList<TcgInventoryItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(TcgInventoryItem.formatAuditPk(user)).build()))
            .build();
    tcgInventoryTable.query(request).items().forEach(results::add);
    return results;
  }

  private APIGatewayV2HTTPEvent buildEvent(String user) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder().withHeaders(Map.of("Authorization", authHeader)).build();
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

  private APIGatewayV2HTTPEvent buildEventWithPath(String user, Map<String, String> pathParams) {
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
