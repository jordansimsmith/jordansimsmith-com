package com.jordansimsmith.tcginventory.orders.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.orders.api.FindOrdersHandler;
import com.jordansimsmith.tcginventory.orders.api.GetOrderHandler;
import com.jordansimsmith.tcginventory.orders.client.ConfirmOrderRequest;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@Testcontainers
public class OrdersHandlerIntegrationTest {

  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private TcgInventoryTestFactory factory;

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
    objectMapper = factory.objectMapper();
    fakeOrdersQueue = factory.fakeOrdersQueue();

    DynamoDbUtils.reset(factory.dynamoDbClient());
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
            new TestOrderRow(skuId1, 1001, 1, "1.50", "2.00", List.of(1)),
            new TestOrderRow(skuId2, 1002, 1, "2.00", "1.80", List.of(3)));
    writeOrder(
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
        1700000000);

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
            new TestOrderRow(magicSkuId, 1001, 1, "2.00", "2.00", List.of(10)),
            new TestOrderRow(pokemonSkuId, 283917, 1, "12.00", "10.00", List.of(10)));
    writeOrder(
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
        1700000000);

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
    writeOrder(
        "jordan",
        "91329",
        "to_pick",
        "ACCEPTED",
        "SEND_TRACKING_CODE",
        "DELIVERY",
        "Chris Andrew (generic)",
        Map.of(
            "line1", "32 Abercrombie Street",
            "suburb", "Howick",
            "city", "Auckland",
            "post_code", "2014",
            "country", "NZ"),
        "Economy Tracked",
        "61.50",
        List.of(),
        1700000000);

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
  void getOrderShouldReturn404ForUnknownOrder() {
    // act
    var response =
        getOrderHandler.handleRequest(
            buildEventWithPath("jordan", Map.of("order_id", "nonexistent")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
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
    assertThat(getOrderState("jordan", "83663")).isEqualTo("fulfilling");
    assertThat(getUnits("jordan", skuId).stream().filter(u -> u.status() == UnitStatus.SOLD))
        .hasSize(1);
    assertThat(getSkuItem("jordan", skuId).version()).isEqualTo(2);
  }

  private void createOrder(
      String user, String offerId, String status, String deliveryMode, String totalPrice) {
    writeOrder(
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
        1700000000);
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
            new TestOrderRow(
                skuId, 1001, sequenceNumbers.size(), linePrice, listedPrice, sequenceNumbers));
    writeOrder(
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
        1700000000);
  }

  private String getOrderState(String user, String orderId) throws Exception {
    var response =
        getOrderHandler.handleRequest(buildEventWithPath(user, Map.of("order_id", orderId)), null);
    assertThat(response.getStatusCode()).isEqualTo(200);
    return objectMapper.readTree(response.getBody()).get("state").asText();
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

  private void writeOrder(
      String user,
      String orderId,
      String status,
      String providerStatus,
      String action,
      String delivery,
      String buyer,
      Map<String, String> address,
      String postage,
      String total,
      List<TestOrderRow> lines,
      long createdAt) {
    for (int position = 0; position < lines.size(); position++) {
      var line = lines.get(position);
      var row = new HashMap<String, AttributeValue>();
      row.put("pk", stringAttribute("USER#" + user + "#ORDER#" + orderId));
      row.put("sk", stringAttribute("ROW#" + String.format(Locale.ROOT, "%010d", position)));
      row.put("user", stringAttribute(user));
      row.put("order_id", stringAttribute(orderId));
      row.put("position", numberAttribute(position));
      row.put("sku_id", stringAttribute(line.skuId()));
      row.put("fetchtcg_listing_id", numberAttribute(line.listingId()));
      row.put("quantity", numberAttribute(line.quantity()));
      row.put("price", stringAttribute(line.price()));
      if (line.listedPrice() != null) {
        row.put("listed_price", stringAttribute(line.listedPrice()));
      }
      row.put(
          "allocated_sequence_numbers",
          AttributeValue.builder()
              .l(line.allocatedSequenceNumbers().stream().map(this::numberAttribute).toList())
              .build());
      factory
          .dynamoDbClient()
          .putItem(PutItemRequest.builder().tableName("tcg_inventory").item(row).build());
    }

    var order = new HashMap<String, AttributeValue>();
    order.put("pk", stringAttribute("USER#" + user));
    order.put(
        "sk", stringAttribute("ORDER#" + "0".repeat(Math.max(0, 20 - orderId.length())) + orderId));
    order.put("order_id", stringAttribute(orderId));
    order.put("status", stringAttribute(status));
    if (providerStatus != null) {
      order.put("fetchtcg_status", stringAttribute(providerStatus));
    }
    if (action != null) {
      order.put("fetchtcg_current_action", stringAttribute(action));
    }
    if (delivery != null) {
      order.put("delivery_mode", stringAttribute(delivery));
    }
    if (buyer != null) {
      order.put("buyer_name", stringAttribute(buyer));
    }
    if (address != null) {
      var addressAttributes = new HashMap<String, AttributeValue>();
      address.forEach((key, value) -> addressAttributes.put(key, stringAttribute(value)));
      order.put("buyer_address", AttributeValue.builder().m(addressAttributes).build());
    }
    if (postage != null) {
      order.put("postage_option", stringAttribute(postage));
    }
    if (total != null) {
      order.put("total_price", stringAttribute(total));
    }
    order.put("row_count", numberAttribute(lines.size()));
    order.put("created_at", numberAttribute(createdAt));
    order.put("updated_at", numberAttribute(createdAt));
    factory
        .dynamoDbClient()
        .putItem(PutItemRequest.builder().tableName("tcg_inventory").item(order).build());
  }

  private AttributeValue stringAttribute(String value) {
    return AttributeValue.builder().s(value).build();
  }

  private AttributeValue numberAttribute(long value) {
    return AttributeValue.builder().n(Long.toString(value)).build();
  }

  private record TestOrderRow(
      String skuId,
      int listingId,
      int quantity,
      String price,
      String listedPrice,
      List<Integer> allocatedSequenceNumbers) {}
}
