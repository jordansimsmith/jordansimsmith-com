package com.jordansimsmith.tcginventory.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.tcginventory.AuditItem;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
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
  private DynamoDbTable<OrderItem> orderTable;
  private DynamoDbTable<SkuItem> skuTable;
  private DynamoDbTable<UnitItem> unitTable;
  private DynamoDbTable<AuditItem> auditTable;

  private FindOrdersHandler findOrdersHandler;
  private GetOrderHandler getOrderHandler;
  private ConfirmOrderHandler confirmOrderHandler;

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
    orderTable = factory.orderTable();
    skuTable = factory.skuTable();
    unitTable = factory.unitTable();
    auditTable = factory.auditTable();

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
  void findOrdersShouldSortNumericallyAcrossIdLengths() throws Exception {
    // arrange
    createOrder("jordan", "99998", "fulfilled", "PICKUP", "1.00");
    createOrder("jordan", "99999", "fulfilled", "PICKUP", "1.00");
    createOrder("jordan", "100000", "to_pick", "SHIPPING", "1.00");
    createOrder("other", "200000", "to_pick", "SHIPPING", "1.00");

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
    assertThat(units.get(0).get("external_source").asText()).isEqualTo("scryfall");
    assertThat(units.get(0).get("external_id").asText()).isEqualTo("scryfall-1");
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
            new OrderItem.OrderLine(skuId1, 1001, 1, "1.50", "2.00", List.of(1)),
            new OrderItem.OrderLine(skuId2, 1002, 1, "2.00", "1.80", List.of(3)));
    var order =
        OrderItem.create(
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
    assertThat(units.get(0).get("external_source").asText()).isEqualTo("scryfall");
    assertThat(units.get(0).get("external_id").asText()).isEqualTo("scryfall-1");
    assertThat(units.get(0).get("price").asText()).isEqualTo("1.50");
    assertThat(units.get(1).get("name").asText()).isEqualTo("Sol Ring");
    assertThat(units.get(1).get("external_source").asText()).isEqualTo("scryfall");
    assertThat(units.get(1).get("external_id").asText()).isEqualTo("scryfall-2");
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
  void getOrderShouldFailWhenSkuIsMissing() {
    // arrange
    var skuId = "mtg#scryfall#missing#normal#NM";
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1), "PICKUP", "1.50", "1.50", null);

    // act
    var assertion =
        assertThatThrownBy(
            () ->
                getOrderHandler.handleRequest(
                    buildEventWithPath("jordan", Map.of("order_id", "83663")), null));

    // assert
    assertion
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("sku record missing: " + SkuItem.formatPk("jordan", skuId));
  }

  @Test
  void getOrderShouldFailWhenSkuGameIsNotRegistered() {
    // arrange
    var skuId = "pokemon#tcgcsv#123#normal#NM";
    skuTable.putItem(
        SkuItem.create(
            "jordan",
            skuId,
            "pokemon",
            "tcgcsv",
            "123",
            "normal",
            "NM",
            "Test Card",
            "set",
            "Test Set",
            "1",
            null,
            null));
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1), "PICKUP", "1.50", "1.50", null);

    // act
    var assertion =
        assertThatThrownBy(
            () ->
                getOrderHandler.handleRequest(
                    buildEventWithPath("jordan", Map.of("order_id", "83663")), null));

    // assert
    assertion
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("unsupported game: pokemon");
  }

  @Test
  void getOrderShouldReturnFulfillmentDetails() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var order =
        OrderItem.create(
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
  void getOrderShouldComputeCurrentLocationForFulfilledOrder() throws Exception {
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
    assertThat(unit.get("location").asText()).isEqualTo("A0-2");
    assertThat(unit.get("current_location").asText()).isEqualTo("A0-1");
    assertThat(unit.get("previous_card").get("name").asText()).isEqualTo("Other Card");
    assertThat(unit.get("next_card").get("name").asText()).isEqualTo("Other Card");
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
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 3);
    reserveUnits("jordan", skuId, "83663", List.of(1, 2));
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1, 2), "PICKUP", "3.00", "3.00", null);

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
    var lines = new ArrayList<OrderItem.OrderLine>();
    for (int i = 1; i <= 60; i++) {
      var skuId = "mtg#scryfall#scryfall-" + i + "#normal#NM";
      createSkuWithUnits("jordan", skuId, 1);
      reserveUnits("jordan", skuId, "83663", List.of(1));
      lines.add(new OrderItem.OrderLine(skuId, 1000 + i, 1, "0.50", "0.50", List.of(1)));
    }
    var order =
        OrderItem.create(
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
    var response =
        confirmOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var updatedOrder = getOrderItem("jordan", "83663");
    assertThat(updatedOrder.getStatus()).isEqualTo("fulfilled");

    for (int i = 1; i <= 60; i++) {
      var units = getUnits("jordan", "mtg#scryfall#scryfall-" + i + "#normal#NM");
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
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 2);

    var sku = getSkuItem("jordan", skuId);
    sku.setDirty(false);
    sku.setGsi1pk(SkuItem.USER_PREFIX + "jordan" + "#CLEAN");
    skuTable.putItem(sku);

    reserveUnits("jordan", skuId, "83663", List.of(1));
    createOrderWithLines(
        "jordan", "83663", "to_pick", skuId, List.of(1), "PICKUP", "1.50", "1.50", null);

    // act
    confirmOrderHandler.handleRequest(
        buildEventWithPath("jordan", Map.of("order_id", "83663")), null);

    // assert
    var updatedSku = getSkuItem("jordan", skuId);
    assertThat(updatedSku.getDirty()).isFalse();
    assertThat(updatedSku.getGsi1pk()).isEqualTo(SkuItem.USER_PREFIX + "jordan" + "#CLEAN");
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
        OrderItem.create(
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
    var skuItem =
        SkuItem.create(
            user,
            skuId,
            parts[0],
            parts[1],
            parts[2],
            parts[3],
            parts[4],
            name,
            setCode,
            "Test Set",
            collectorNumber,
            null,
            null);
    skuTable.putItem(skuItem);
  }

  private void createUnit(
      String user, String skuId, int sequenceNumber, String status, String orderId) {
    var unit =
        UnitItem.create(
            user,
            "mtg",
            skuId,
            sequenceNumber,
            status,
            "import1",
            Instant.ofEpochSecond(1700000000));
    unit.setOrderId(orderId);
    unitTable.putItem(unit);
  }

  private void reserveUnits(
      String user, String skuId, String orderId, List<Integer> sequenceNumbers) {
    for (var seqNum : sequenceNumbers) {
      var unit =
          unitTable.getItem(
              Key.builder()
                  .partitionValue(SkuItem.formatPk(user, skuId))
                  .sortValue(UnitItem.formatSk(seqNum))
                  .build());
      unit.setStatus("reserved");
      unit.setOrderId(orderId);
      unitTable.putItem(unit);
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
            new OrderItem.OrderLine(
                skuId, 1001, sequenceNumbers.size(), linePrice, listedPrice, sequenceNumbers));
    var order =
        OrderItem.create(
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
            .partitionValue(SkuItem.formatUserPk(user))
            .sortValue(OrderItem.formatSk(offerId))
            .build());
  }

  private SkuItem getSkuItem(String user, String skuId) {
    return skuTable.getItem(
        Key.builder()
            .partitionValue(SkuItem.formatPk(user, skuId))
            .sortValue(SkuItem.formatSk())
            .build());
  }

  private List<UnitItem> getUnits(String user, String skuId) {
    var results = new ArrayList<UnitItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(SkuItem.formatPk(user, skuId))
                        .sortValue(UnitItem.UNIT_PREFIX)
                        .build()))
            .build();
    unitTable.query(request).items().forEach(results::add);
    return results;
  }

  private List<AuditItem> getAuditEntries(String user) {
    var results = new ArrayList<AuditItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(AuditItem.formatPk(user)).build()))
            .build();
    auditTable.query(request).items().forEach(results::add);
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
