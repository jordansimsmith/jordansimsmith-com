package com.jordansimsmith.tcginventory.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.SellUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.orders.api.FindOrdersHandler;
import com.jordansimsmith.tcginventory.orders.api.GetOrderHandler;
import com.jordansimsmith.tcginventory.orders.client.ConfirmOrderRequest;
import com.jordansimsmith.tcginventory.orders.server.FulfillmentMessage;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@Testcontainers
public class OrdersHandlerIntegrationTest {

  private FakeClock fakeClock;
  private FakeUlidGenerator fakeUlidGenerator;
  private ObjectMapper objectMapper;
  private TcgInventoryTestFactory factory;
  private DynamoDbTable<OrderItem> orderTable;
  private DynamoDbTable<JobItem> jobTable;

  private FindOrdersHandler findOrdersHandler;
  private GetOrderHandler getOrderHandler;
  private FakeQueueClient<FulfillmentMessage> fakeOrdersQueue;

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
    jobTable = factory.jobTable();
    fakeOrdersQueue = factory.fakeOrdersQueue();
    orderTable = factory.orderTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeUlidGenerator.reset();
    fakeOrdersQueue.reset();

    findOrdersHandler = new FindOrdersHandler(factory);
    getOrderHandler = new GetOrderHandler(factory);
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
    createOrder("jordan", "10002", "to_pick", "DELIVERY", "5.50");

    // act
    var response = findOrdersHandler.handleRequest(buildEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var orders = body.get("orders");
    assertThat(orders).hasSize(2);
    assertThat(orders.get(0).get("order_id").asText()).isEqualTo("10002");
    assertThat(orders.get(0).get("state").asText()).isEqualTo("to_pick");
    assertThat(orders.get(0).get("delivery_mode").asText()).isEqualTo("DELIVERY");
    assertThat(orders.get(0).get("total_price").asText()).isEqualTo("5.50");
    assertThat(orders.get(0).get("accepted_at").asLong()).isEqualTo(1700000000);
    assertThat(orders.get(0).get("items_total_price").isNull()).isTrue();
    assertThat(orders.get(0).get("listed_total_price").isNull()).isTrue();
    assertThat(orders.get(1).get("order_id").asText()).isEqualTo("10001");
    assertThat(orders.get(1).get("state").asText()).isEqualTo("awaiting_payment");
    assertThat(body.get("next_continuation").isNull()).isTrue();
  }

  @Test
  void findOrdersShouldSortNumericallyAcrossIdLengths() throws Exception {
    // arrange
    createOrder("jordan", "99998", "fulfilled", "PICKUP", "1.00");
    createOrder("jordan", "99999", "fulfilled", "PICKUP", "1.00");
    createOrder("jordan", "100000", "to_pick", "DELIVERY", "1.00");
    createOrder("other", "200000", "to_pick", "DELIVERY", "1.00");

    // act
    var response1 =
        findOrdersHandler.handleRequest(
            buildEventWithQuery("jordan", Map.of(), Map.of("limit", "2")), null);
    var body1 = objectMapper.readTree(response1.getBody());
    var response2 =
        findOrdersHandler.handleRequest(
            buildEventWithQuery(
                "jordan",
                Map.of(),
                Map.of("continuation", body1.get("next_continuation").asText())),
            null);
    var body2 = objectMapper.readTree(response2.getBody());

    // assert
    assertThat(body1.get("orders").get(0).get("order_id").asText()).isEqualTo("100000");
    assertThat(body1.get("orders").get(1).get("order_id").asText()).isEqualTo("99999");
    assertThat(body2.get("orders").get(0).get("order_id").asText()).isEqualTo("99998");
    assertThat(body2.get("next_continuation").isNull()).isTrue();
  }

  @Test
  void findOrdersShouldSupportContinuationPaging() throws Exception {
    // arrange
    createOrder("jordan", "10001", "awaiting_payment", "PICKUP", "2.00");
    createOrder("jordan", "10002", "to_pick", "DELIVERY", "5.50");
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
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 2);
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1, 2), "PICKUP", "3.00", "1.50", "2.00");

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
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 1);
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1), "PICKUP", "1.50", "1.50", null);

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
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 3);
    reserveUnits("jordan", skuId, "83663", List.of(1, 3));
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1, 3), "PICKUP", "3.33", "3.33", "3.50");

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
    assertThat(body.get("items_total_price").asText()).isEqualTo("3.33");
    assertThat(body.get("listed_total_price").asText()).isEqualTo("7.00");
    assertThat(body.get("unit_count").asInt()).isEqualTo(2);

    var lines = body.get("lines");
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).get("game").asText()).isEqualTo("mtg");
    assertThat(lines.get(0).get("name").asText()).isEqualTo("Test Card");
    assertThat(lines.get(0).get("set_code").asText()).isEqualTo("dom");
    assertThat(lines.get(0).get("collector_number").asText()).isEqualTo("168");
    assertThat(lines.get(0).get("finish").asText()).isEqualTo("normal");
    assertThat(lines.get(0).get("condition").asText()).isEqualTo("NM");
    assertThat(lines.get(0).get("quantity").asInt()).isEqualTo(2);
    assertThat(lines.get(0).get("price").asText()).isEqualTo("3.33");
    assertThat(lines.get(0).get("listed_price").asText()).isEqualTo("3.50");

    var units = body.get("units");
    assertThat(units).hasSize(2);
    assertThat(units.get(0).get("game").asText()).isEqualTo("mtg");
    assertThat(units.get(0).get("sequence_number").asInt()).isEqualTo(1);
    assertThat(units.get(0).get("location").asText()).isEqualTo("A0-1");
    assertThat(units.get(0).get("current_location").asText()).isEqualTo("A0-0");
    assertThat(units.get(0).has("external_source")).isFalse();
    assertThat(units.get(0).get("external_id").asText()).isEqualTo("scryfall-1");
    assertThat(units.get(0).has("image_urls")).isFalse();
    assertThat(units.get(0).has("scryfall_id")).isFalse();
    assertThat(units.get(0).get("name").asText()).isEqualTo("Test Card");
    assertThat(units.get(0).get("set_code").asText()).isEqualTo("dom");
    assertThat(units.get(0).get("collector_number").asText()).isEqualTo("168");
    assertThat(units.get(0).get("finish").asText()).isEqualTo("normal");
    assertThat(units.get(0).get("condition").asText()).isEqualTo("NM");
    assertThat(units.get(0).get("price").asText()).isEqualTo("1.67");
    assertThat(units.get(0).get("previous_card").isNull()).isTrue();
    assertThat(units.get(0).get("next_card").get("name").asText()).isEqualTo("Test Card");
    assertThat(units.get(0).get("next_card").has("external_id")).isFalse();
    assertThat(units.get(1).get("sequence_number").asInt()).isEqualTo(3);
    assertThat(units.get(1).get("location").asText()).isEqualTo("A0-3");
    assertThat(units.get(1).get("current_location").asText()).isEqualTo("A0-2");
    assertThat(units.get(1).get("price").asText()).isEqualTo("1.67");
    assertThat(units.get(1).get("previous_card").get("name").asText()).isEqualTo("Test Card");
    assertThat(units.get(1).get("next_card").isNull()).isTrue();
  }

  @Test
  void getOrderShouldReturnUnitsFromMultipleSkus() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId1 = "mtg#scryfall#scryfall-1#normal#NM";
    var skuId2 = "mtg#scryfall#scryfall-2#foil#LP";
    createSku("jordan", skuId1, "Lightning Bolt", "dom", "168");
    createSku("jordan", skuId2, "Sol Ring", "c21", "100");
    createUnit("jordan", skuId1, 1, "reserved", "83663");
    createUnit("jordan", skuId1, 2, "in_stock", null);
    createUnit("jordan", skuId2, 3, "reserved", "83663");
    createUnit("jordan", skuId2, 4, "in_stock", null);

    var lines =
        List.of(
            new OrderRowItem(skuId1, 1001, 1, "1.50", "2.00", List.of(1)),
            new OrderRowItem(skuId2, 1002, 1, "2.00", "1.80", List.of(3)));
    var order =
        createOrderItem(
            "jordan",
            "83663",
            "to_pick",
            "ACCEPTED",
            "SEND_PICKUP_ADDRESS",
            "PICKUP",
            null,
            null,
            null,
            "3.50",
            lines,
            Instant.ofEpochSecond(1700000000));
    orderTable.putItem(order);

    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var units = body.get("units");
    assertThat(units).hasSize(2);
    assertThat(units.get(0).get("name").asText()).isEqualTo("Lightning Bolt");
    assertThat(units.get(0).has("external_source")).isFalse();
    assertThat(units.get(0).get("external_id").asText()).isEqualTo("scryfall-1");
    assertThat(units.get(0).has("image_urls")).isFalse();
    assertThat(units.get(0).get("price").asText()).isEqualTo("1.50");
    assertThat(units.get(1).get("name").asText()).isEqualTo("Sol Ring");
    assertThat(units.get(1).has("external_source")).isFalse();
    assertThat(units.get(1).get("external_id").asText()).isEqualTo("scryfall-2");
    assertThat(units.get(1).has("image_urls")).isFalse();
    assertThat(units.get(1).get("price").asText()).isEqualTo("2.00");

    var responseLines = body.get("lines");
    assertThat(responseLines).hasSize(2);
    assertThat(responseLines.get(0).get("name").asText()).isEqualTo("Lightning Bolt");
    assertThat(responseLines.get(0).get("price").asText()).isEqualTo("1.50");
    assertThat(responseLines.get(0).get("listed_price").asText()).isEqualTo("2.00");
    assertThat(responseLines.get(1).get("name").asText()).isEqualTo("Sol Ring");
    assertThat(responseLines.get(1).get("listed_price").asText()).isEqualTo("1.80");
  }

  @Test
  void getOrderShouldKeepMixedGameImagesAndLocationsIsolated() throws Exception {
    // arrange
    var magicSkuId = "mtg#scryfall#scryfall-1#normal#NM";
    var pokemonSkuId = "pokemon#tcgplayer#283917#normal#NM";
    createSku("jordan", magicSkuId, "Magic Card", "dom", "168");
    createSku("jordan", pokemonSkuId, "Abomasnow", "lost-origin", "043/196");
    createUnit("jordan", magicSkuId, 10, "reserved", "83664");
    createUnit("jordan", magicSkuId, 20, "in_stock", null);
    createUnit("jordan", pokemonSkuId, 10, "reserved", "83664");
    createUnit("jordan", pokemonSkuId, 20, "in_stock", null);
    var lines =
        List.of(
            new OrderRowItem(magicSkuId, 1001, 1, "2.00", "2.00", List.of(10)),
            new OrderRowItem(pokemonSkuId, 283917, 1, "12.00", "10.00", List.of(10)));
    orderTable.putItem(
        createOrderItem(
            "jordan",
            "83664",
            "to_pick",
            "ACCEPTED",
            "SEND_DELIVERY_ADDRESS",
            "DELIVERY",
            null,
            null,
            null,
            "14.00",
            lines,
            Instant.ofEpochSecond(1700000000)));

    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83664")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("lines").get(0).get("game").asText()).isEqualTo("mtg");
    assertThat(body.get("lines").get(1).get("game").asText()).isEqualTo("pokemon");
    var units = body.get("units");
    assertThat(units).hasSize(2);
    assertThat(units.get(0).get("game").asText()).isEqualTo("mtg");
    assertThat(units.get(0).get("current_location").asText()).isEqualTo("A0-0");
    assertThat(units.get(0).get("next_card").get("name").asText()).isEqualTo("Magic Card");
    assertThat(units.get(1).get("game").asText()).isEqualTo("pokemon");
    assertThat(units.get(0).has("image_urls")).isFalse();
    assertThat(units.get(1).has("image_urls")).isFalse();
    assertThat(units.get(1).get("current_location").asText()).isEqualTo("A0-0");
    assertThat(units.get(1).get("next_card").get("name").asText()).isEqualTo("Abomasnow");
  }

  @Test
  void getOrderShouldFailWhenSkuIsMissing() {
    // arrange
    var skuId = "mtg#scryfall#missing#normal#NM";
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1), "PICKUP", "1.50", "1.50", null);

    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void getOrderShouldReturnCatalogReferenceWithoutResolvingGameMetadata() throws Exception {
    // arrange
    var skuId = "digimon#unknown#123#normal#NM";
    factory
        .fakeInventoryService()
        .seedSku(
            "jordan",
            new Sku(
                skuId,
                "digimon",
                "123",
                "Test Card",
                "set",
                "Test Set",
                "1",
                "normal",
                Condition.NM,
                null,
                null,
                0,
                1),
            false);
    createOrderWithLines(
        "jordan", "83663", "fulfilled", skuId, List.of(1), "PICKUP", "1.50", "1.50", null);

    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("units").get(0).get("game").asText()).isEqualTo("digimon");
    assertThat(body.get("units").get(0).get("external_id").asText()).isEqualTo("123");
    assertThat(body.get("units").get(0).has("image_urls")).isFalse();
  }

  @Test
  void getOrderShouldReturnFulfillmentDetails() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var order =
        createOrderItem(
            "jordan",
            "91329",
            "to_pick",
            "ACCEPTED",
            "SEND_TRACKING_CODE",
            "DELIVERY",
            "Chris Andrew (generic)",
            OrderItem.BuyerAddress.create(
                "32 Abercrombie Street", null, "Howick", "Auckland", "2014", "NZ"),
            "Economy Tracked",
            "61.50",
            List.of(),
            Instant.ofEpochSecond(1700000000));
    orderTable.putItem(order);

    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "91329")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("buyer_name").asText()).isEqualTo("Chris Andrew (generic)");
    assertThat(body.get("postage_option").asText()).isEqualTo("Economy Tracked");
    var address = body.get("buyer_address");
    assertThat(address.get("line1").asText()).isEqualTo("32 Abercrombie Street");
    assertThat(address.get("line2").isNull()).isTrue();
    assertThat(address.get("suburb").asText()).isEqualTo("Howick");
    assertThat(address.get("city").asText()).isEqualTo("Auckland");
    assertThat(address.get("post_code").asText()).isEqualTo("2014");
    assertThat(address.get("country").asText()).isEqualTo("NZ");
  }

  @Test
  void getOrderShouldReturnNullFulfillmentDetailsForLegacyOrders() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createOrder("jordan", "83663", "to_pick", "PICKUP", "3.33");

    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("buyer_name").isNull()).isTrue();
    assertThat(body.get("buyer_address").isNull()).isTrue();
    assertThat(body.get("postage_option").isNull()).isTrue();
  }

  @Test
  void getOrderShouldReturnNullListedPriceForLegacyOrders() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 1);
    reserveUnits("jordan", skuId, "83663", List.of(1));
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1), "PICKUP", "1.50", "1.50", null);

    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("items_total_price").asText()).isEqualTo("1.50");
    assertThat(body.get("listed_total_price").isNull()).isTrue();
    var lines = body.get("lines");
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).get("price").asText()).isEqualTo("1.50");
    assertThat(lines.get(0).get("listed_price").isNull()).isTrue();
    assertThat(body.get("units").get(0).get("price").asText()).isEqualTo("1.50");
  }

  @Test
  void getOrderShouldReturnCurrentLocationAccountingForSoldAndRemovedGaps() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    var otherSkuId = "mtg#scryfall#scryfall-2#normal#NM";
    createSku("jordan", skuId, "Test Card", "dom", "168");
    createSku("jordan", otherSkuId, "Other Card", "c21", "100");
    createUnit("jordan", skuId, 10, "sold", null);
    createUnit("jordan", skuId, 20, "removed", null);
    createUnit("jordan", skuId, 30, "in_stock", null);
    createUnit("jordan", otherSkuId, 40, "in_stock", null);
    createUnit("jordan", skuId, 50, "reserved", "83663");
    createUnit("jordan", otherSkuId, 60, "in_stock", null);
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(50), "PICKUP", "2.00", "2.00", "2.50");

    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var unit = body.get("units").get(0);
    assertThat(unit.get("location").asText()).isEqualTo("A0-50");
    assertThat(unit.get("current_location").asText()).isEqualTo("A0-2");
    assertThat(unit.get("previous_card").get("name").asText()).isEqualTo("Other Card");
    assertThat(unit.get("previous_card").get("set_code").asText()).isEqualTo("c21");
    assertThat(unit.get("previous_card").get("collector_number").asText()).isEqualTo("100");
    assertThat(unit.get("previous_card").get("finish").asText()).isEqualTo("normal");
    assertThat(unit.get("previous_card").get("condition").asText()).isEqualTo("NM");
    assertThat(unit.get("next_card").get("name").asText()).isEqualTo("Other Card");
  }

  @Test
  void getOrderShouldCountReservedUnitsAsStillBoxed() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    var otherSkuId = "mtg#scryfall#scryfall-2#normal#NM";
    createSku("jordan", skuId, "Test Card", "dom", "168");
    createSku("jordan", otherSkuId, "Other Card", "c21", "100");
    createUnit("jordan", otherSkuId, 3, "in_stock", null);
    createUnit("jordan", skuId, 5, "reserved", "83663");
    createUnit("jordan", skuId, 6, "reserved", "83663");
    createUnit("jordan", otherSkuId, 8, "in_stock", null);
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(5, 6), "PICKUP", "3.00", "3.00", "2.00");

    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert - a snapshot of the box: every reserved unit, including this order's own,
    // still occupies its slot
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var units = body.get("units");

    assertThat(units.get(0).get("sequence_number").asInt()).isEqualTo(5);
    assertThat(units.get(0).get("current_location").asText()).isEqualTo("A0-1");
    assertThat(units.get(0).get("previous_card").get("name").asText()).isEqualTo("Other Card");
    assertThat(units.get(0).get("next_card").get("name").asText()).isEqualTo("Test Card");

    assertThat(units.get(1).get("sequence_number").asInt()).isEqualTo(6);
    assertThat(units.get(1).get("current_location").asText()).isEqualTo("A0-2");
    assertThat(units.get(1).get("previous_card").get("name").asText()).isEqualTo("Test Card");
    assertThat(units.get(1).get("next_card").get("name").asText()).isEqualTo("Other Card");
  }

  @Test
  void getOrderShouldReturnNullNeighborsAtBlockEdges() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    var otherSkuId = "mtg#scryfall#scryfall-2#normal#NM";
    createSku("jordan", skuId, "Test Card", "dom", "168");
    createSku("jordan", otherSkuId, "Other Card", "c21", "100");
    createUnit("jordan", skuId, 0, "reserved", "83663");
    createUnit("jordan", skuId, 105, "reserved", "83663");
    createUnit("jordan", otherSkuId, 110, "in_stock", null);
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(0, 105), "PICKUP", "4.00", "4.00", "2.50");

    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var units = body.get("units");

    // block A0 holds nothing else, and block A1 units never bleed across the boundary
    assertThat(units.get(0).get("location").asText()).isEqualTo("A0-0");
    assertThat(units.get(0).get("current_location").asText()).isEqualTo("A0-0");
    assertThat(units.get(0).get("previous_card").isNull()).isTrue();
    assertThat(units.get(0).get("next_card").isNull()).isTrue();

    assertThat(units.get(1).get("location").asText()).isEqualTo("A1-5");
    assertThat(units.get(1).get("current_location").asText()).isEqualTo("A1-0");
    assertThat(units.get(1).get("previous_card").isNull()).isTrue();
    assertThat(units.get(1).get("next_card").get("name").asText()).isEqualTo("Other Card");
  }

  @Test
  void getOrderShouldReturnAllocatedCardsWithoutLocationsForFulfilledOrder() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    var otherSkuId = "mtg#scryfall#scryfall-2#normal#NM";
    createSku("jordan", skuId, "Test Card", "dom", "168");
    createSku("jordan", otherSkuId, "Other Card", "c21", "100");
    createUnit("jordan", otherSkuId, 1, "in_stock", null);
    createUnit("jordan", skuId, 2, "sold", "83663");
    createUnit("jordan", otherSkuId, 3, "in_stock", null);
    createOrderWithLines(
        "jordan", "83663", "fulfilled", skuId, List.of(2), "PICKUP", "1.50", "1.50", "2.00");

    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var unit = body.get("units").get(0);
    assertThat(unit.get("name").asText()).isEqualTo("Test Card");
    assertThat(unit.get("location").isNull()).isTrue();
    assertThat(unit.get("current_location").isNull()).isTrue();
    assertThat(unit.get("previous_card").isNull()).isTrue();
    assertThat(unit.get("next_card").isNull()).isTrue();
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
  void confirmOrderShouldQueueFulfillmentAndMarkUnitsSold() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 3);
    reserveUnits("jordan", skuId, "83663", List.of(1, 2));
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1, 2), "PICKUP", "3.00", "3.00", null);

    // act
    factory
        .orderService()
        .confirmOrder(new RequestContext("jordan"), new ConfirmOrderRequest("83663"));

    // assert
    assertThat(getOrderItem("jordan", "83663").getStatus()).isEqualTo("fulfilling");
    assertThat(fakeOrdersQueue.getSends())
        .containsExactly(
            new FakeQueueClient.SentMessage<>(
                new FulfillmentMessage("jordan", "83663"), "83663", "83663"));
    var units = getUnits("jordan", skuId);
    assertThat(units.stream().filter(u -> u.status() == UnitStatus.RESERVED).toList()).hasSize(2);
    assertThat(units.stream().filter(u -> u.status() == UnitStatus.IN_STOCK).toList()).hasSize(1);

    // act - a duplicate request uses the existing admission without another message
    factory
        .orderService()
        .confirmOrder(new RequestContext("jordan"), new ConfirmOrderRequest("83663"));
    factory
        .fulfillmentMessageHandler()
        .handleMessage(new RequestContext("jordan"), fakeOrdersQueue.getMessages().getFirst());

    // assert
    assertThat(fakeOrdersQueue.getMessages()).hasSize(1);
    assertThat(getOrderItem("jordan", "83663").getStatus()).isEqualTo("fulfilled");
    units = getUnits("jordan", skuId);
    assertThat(units.stream().filter(u -> u.status() == UnitStatus.SOLD).toList()).hasSize(2);
    assertThat(units.stream().filter(u -> u.status() == UnitStatus.IN_STOCK).toList()).hasSize(1);
    assertThat(getSkuItem("jordan", skuId).version()).isEqualTo(3);

    // act - a fulfilled request remains a no-op
    factory
        .orderService()
        .confirmOrder(new RequestContext("jordan"), new ConfirmOrderRequest("83663"));

    // assert
    assertThat(fakeOrdersQueue.getMessages()).hasSize(1);
  }

  @Test
  void confirmOrderShouldSellLargeOrderAcrossTransactions() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var lines = new ArrayList<OrderRowItem>();
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    var sequenceNumbers = new ArrayList<Integer>();
    for (int i = 1; i <= 101; i++) {
      sequenceNumbers.add(i);
    }
    createSkuWithUnits("jordan", skuId, sequenceNumbers.size());
    reserveUnits("jordan", skuId, "83663", sequenceNumbers);
    lines.add(
        new OrderRowItem(skuId, 1001, sequenceNumbers.size(), "50.50", "0.50", sequenceNumbers));
    var order =
        createOrderItem(
            "jordan",
            "83663",
            "to_pick",
            "ACCEPTED",
            "SEND_PICKUP_ADDRESS",
            "PICKUP",
            null,
            null,
            null,
            "30.00",
            lines,
            Instant.ofEpochSecond(1700000000));
    orderTable.putItem(order);

    // act
    factory
        .orderService()
        .confirmOrder(new RequestContext("jordan"), new ConfirmOrderRequest("83663"));
    factory
        .fulfillmentMessageHandler()
        .handleMessage(new RequestContext("jordan"), fakeOrdersQueue.getMessages().getFirst());

    // assert
    var updatedOrder = getOrderItem("jordan", "83663");
    assertThat(updatedOrder.getStatus()).isEqualTo("fulfilled");

    var units = getUnits("jordan", skuId);
    assertThat(units).hasSize(101);
    assertThat(units).allSatisfy(unit -> assertThat(unit.status()).isEqualTo(UnitStatus.SOLD));
  }

  @Test
  void confirmOrderShouldNotSetDirtyFlag() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 2);

    seedSkuDirty("jordan", skuId, false);

    reserveUnits("jordan", skuId, "83663", List.of(1));
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1), "PICKUP", "1.50", "1.50", null);

    // act
    factory
        .orderService()
        .confirmOrder(new RequestContext("jordan"), new ConfirmOrderRequest("83663"));
    factory
        .fulfillmentMessageHandler()
        .handleMessage(new RequestContext("jordan"), fakeOrdersQueue.getMessages().getFirst());

    // assert
    var updatedSku = getSkuItem("jordan", skuId);
    assertThat(isDirty("jordan", skuId)).isFalse();
    assertThat(updatedSku.version()).isEqualTo(2);
  }

  @Test
  void fulfillmentShouldFailWhenAUnitHasAnotherReservationOwner() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 2);
    reserveUnits("jordan", skuId, "83663", List.of(1, 2));
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1, 2), "PICKUP", "3.00", "3.00", null);
    factory
        .orderService()
        .confirmOrder(new RequestContext("jordan"), new ConfirmOrderRequest("83663"));
    var secondUnit =
        getUnits("jordan", skuId).stream()
            .filter(u -> u.sequenceNumber() == 2)
            .findFirst()
            .orElseThrow();
    seedUnit(
        "jordan",
        skuId,
        new Unit(
            secondUnit.sequenceNumber(),
            secondUnit.location(),
            secondUnit.status(),
            secondUnit.photos(),
            secondUnit.createdAt(),
            secondUnit.updatedAt(),
            "another-order"));

    // act - the first sale commits before the second reservation owner is rejected
    assertThatThrownBy(
            () ->
                factory
                    .fulfillmentMessageHandler()
                    .handleMessage(
                        new RequestContext("jordan"), fakeOrdersQueue.getMessages().getFirst()))
        .hasMessage("unit is not reserved for this reservation");

    // assert
    assertThat(getOrderItem("jordan", "83663").getStatus()).isEqualTo("fulfilling");
    assertThat(getUnits("jordan", skuId).stream().filter(u -> u.status() == UnitStatus.SOLD))
        .hasSize(1);
    assertThat(getSkuItem("jordan", skuId).version()).isEqualTo(2);
  }

  @Test
  void fulfillmentShouldResumeAfterACommittedSaleAndRuntimeFailure() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 2);
    reserveUnits("jordan", skuId, "83663", List.of(1, 2));
    createOrderWithLines(
        "jordan", "83663", "fulfilling", skuId, List.of(1, 2), "PICKUP", "3.00", "3.00", null);
    var orderRepository =
        new OrderRepository(
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), OrderRowItem.class),
            jobTable,
            factory.dynamoDbClient(),
            fakeClock);
    var actualInventoryService = factory.inventoryService();
    var inventoryService = mock(InventoryService.class);
    var callCount = new AtomicInteger();
    doAnswer(
            invocation -> {
              var ctx = invocation.getArgument(0, RequestContext.class);
              var request = invocation.getArgument(1, SellUnitRequest.class);
              var response = actualInventoryService.sellUnit(ctx, request);
              if (callCount.incrementAndGet() == 2) {
                throw new RuntimeException("lost response after sale committed");
              }
              return response;
            })
        .when(inventoryService)
        .sellUnit(any(), any());
    var processor = new OrderFulfillmentJobProcessor(orderTable, inventoryService, orderRepository);
    var job =
        JobItem.create(
            "jordan", "fulfillment-retry", "order_fulfillment", null, "83663", Instant.EPOCH);

    // act - the second sale commits, then its response is lost
    assertThatThrownBy(() -> processor.processBatch("jordan", job))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("lost response after sale committed");

    // assert - retry uses Inventory's lifecycle evidence for both completed sales
    assertThat(getOrderItem("jordan", "83663").getStatus()).isEqualTo("fulfilling");
    assertThat(getUnits("jordan", skuId))
        .allSatisfy(unit -> assertThat(unit.status()).isEqualTo(UnitStatus.SOLD));
    var versionAfterSales = getSkuItem("jordan", skuId).version();

    processor.processBatch("jordan", job);

    assertThat(getOrderItem("jordan", "83663").getStatus()).isEqualTo("fulfilled");
    assertThat(getSkuItem("jordan", skuId).version()).isEqualTo(versionAfterSales);
  }

  private void createOrder(
      String user, String offerId, String status, String deliveryMode, String totalPrice) {
    var order =
        createOrderItem(
            user,
            offerId,
            status,
            "ACCEPTED",
            null,
            deliveryMode,
            null,
            null,
            null,
            totalPrice,
            List.of(),
            Instant.ofEpochSecond(1700000000));
    orderTable.putItem(order);
  }

  private void createSkuWithUnits(String user, String skuId, int unitCount) {
    createSku(user, skuId, "Test Card", "dom", "168");
    for (int i = 1; i <= unitCount; i++) {
      createUnit(user, skuId, i, "in_stock", null);
    }
  }

  private void createSku(
      String user, String skuId, String name, String setCode, String collectorNumber) {
    var parts = skuId.split("#");
    factory
        .fakeInventoryService()
        .seedSku(
            user,
            new Sku(
                skuId,
                parts[0],
                parts[2],
                name,
                setCode,
                "Test Set",
                collectorNumber,
                parts[3],
                Condition.valueOf(parts[4]),
                new BigDecimal("1.50"),
                null,
                0,
                1),
            true);
  }

  private void createUnit(
      String user, String skuId, int sequenceNumber, String status, String orderId) {
    var units = new ArrayList<>(getUnits(user, skuId));
    units.add(
        new Unit(
            sequenceNumber,
            "A0-" + sequenceNumber,
            UnitStatus.valueOf(status.toUpperCase()),
            List.of(),
            Instant.ofEpochSecond(1700000000).getEpochSecond(),
            null,
            orderId));
    factory.fakeInventoryService().seedUnits(user, skuId, units);
  }

  private void seedUnit(String user, String skuId, Unit updated) {
    var units = new ArrayList<>(getUnits(user, skuId));
    units.replaceAll(unit -> unit.sequenceNumber() == updated.sequenceNumber() ? updated : unit);
    factory.fakeInventoryService().seedUnits(user, skuId, units);
  }

  private void reserveUnits(
      String user, String skuId, String orderId, List<Integer> sequenceNumbers) {
    for (var sequenceNumber : sequenceNumbers) {
      var unit =
          getUnits(user, skuId).stream()
              .filter(candidate -> candidate.sequenceNumber() == sequenceNumber)
              .findFirst()
              .orElseThrow();
      seedUnit(
          user,
          skuId,
          new Unit(
              unit.sequenceNumber(),
              unit.location(),
              UnitStatus.RESERVED,
              unit.photos(),
              unit.createdAt(),
              unit.updatedAt(),
              orderId));
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
      String linePrice,
      String listedPrice) {
    var lines =
        List.of(
            new OrderRowItem(
                skuId, 1001, sequenceNumbers.size(), linePrice, listedPrice, sequenceNumbers));
    var order =
        createOrderItem(
            user,
            offerId,
            status,
            "ACCEPTED",
            "SEND_PICKUP_ADDRESS",
            deliveryMode,
            null,
            null,
            null,
            totalPrice,
            lines,
            Instant.ofEpochSecond(1700000000));
    orderTable.putItem(order);
  }

  private OrderItem getOrderItem(String user, String offerId) {
    return orderTable.getItem(
        Key.builder()
            .partitionValue(OrderItem.formatPk(user))
            .sortValue(OrderItem.formatSk(offerId))
            .build());
  }

  private Sku getSkuItem(String user, String skuId) {
    try {
      return factory
          .inventoryService()
          .getSku(new RequestContext(user), new GetSkuRequest(skuId))
          .sku();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private List<Unit> getUnits(String user, String skuId) {
    try {
      var units = new ArrayList<Unit>();
      String continuation = null;
      do {
        var response =
            factory
                .inventoryService()
                .findUnits(
                    new RequestContext(user), new FindUnitsRequest(skuId, 100, continuation));
        units.addAll(response.units());
        continuation = response.nextContinuation();
      } while (continuation != null);
      return units;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void seedSkuDirty(String user, String skuId, boolean dirty) {
    factory.fakeInventoryService().seedSku(user, getSkuItem(user, skuId), dirty);
  }

  private boolean isDirty(String user, String skuId) {
    try {
      return factory
          .inventoryService()
          .findSkus(new RequestContext(user), new FindSkusRequest(null, null, true, 100, null))
          .skus()
          .stream()
          .anyMatch(sku -> sku.skuId().equals(skuId));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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

  private OrderItem createOrderItem(
      String user,
      String orderId,
      String status,
      String providerStatus,
      String action,
      String delivery,
      String buyer,
      OrderItem.BuyerAddress address,
      String postage,
      String total,
      List<OrderRowItem> lines,
      Instant createdAt) {
    var table = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), OrderRowItem.class);
    for (int position = 0; position < lines.size(); position++) {
      var line = lines.get(position);
      table.putItem(
          OrderRowItem.create(
              user,
              orderId,
              position,
              line.getSkuId(),
              line.getFetchtcgListingId(),
              line.getQuantity(),
              line.getPrice(),
              line.getListedPrice(),
              line.getAllocatedSequenceNumbers()));
    }
    return OrderItem.create(
        user,
        orderId,
        status,
        providerStatus,
        action,
        delivery,
        buyer,
        address,
        postage,
        total,
        lines.size(),
        createdAt);
  }
}
