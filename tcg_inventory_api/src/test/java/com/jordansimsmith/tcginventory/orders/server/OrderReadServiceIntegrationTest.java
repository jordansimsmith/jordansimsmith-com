package com.jordansimsmith.tcginventory.orders.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitLocationsRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitLocationsResponse;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuResponse;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.UnitLocation;
import com.jordansimsmith.tcginventory.inventory.client.UnitReference;
import com.jordansimsmith.tcginventory.orders.client.DeliveryMode;
import com.jordansimsmith.tcginventory.orders.client.FindOrdersRequest;
import com.jordansimsmith.tcginventory.orders.client.FindOrdersResponse;
import com.jordansimsmith.tcginventory.orders.client.GetOrderRequest;
import com.jordansimsmith.tcginventory.orders.client.OrderState;
import com.jordansimsmith.tcginventory.orders.client.OrderSummary;
import com.jordansimsmith.tcginventory.orders.client.OrderSummaryRow;
import com.jordansimsmith.tcginventory.orders.client.OrderUnit;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Testcontainers
class OrderReadServiceIntegrationTest {
  private static final String TABLE_NAME = "orders_read_test";
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<OrderItem> orderTable;
  private static DynamoDbTable<OrderRowItem> rowTable;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private InventoryService inventoryService;
  private FindOrdersService service;
  private GetOrderService getOrderService;

  @BeforeAll
  static void setUpBeforeClass() {
    dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    orderTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(OrderItem.class));
    rowTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(OrderRowItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, orderTable);
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    service = new FindOrdersService(new OrderReadRepository(orderTable, rowTable), objectMapper);
    inventoryService = mock(InventoryService.class);
    getOrderService =
        new GetOrderService(new OrderReadRepository(orderTable, rowTable), inventoryService);
  }

  @Test
  void findOrdersShouldReturnNewestFirstWithRowTotalsAndUserIsolation() throws Exception {
    // arrange
    orderTable.putItem(order("alice", "1", "fulfilled", "DELIVERY", "19.00", 2, 1700000000));
    orderTable.putItem(order("alice", "2", "to_pick", "PICKUP", "8.00", 1, 1700000100));
    orderTable.putItem(order("bob", "3", "fulfilled", "DELIVERY", "100.00", 0, 1700000200));
    rowTable.putItem(row("alice", "1", 0, "sku-a", 2, "10.00", "12.00"));
    rowTable.putItem(row("alice", "1", 1, "sku-b", 1, "4.50", null));
    rowTable.putItem(row("alice", "2", 0, "sku-c", 1, "8.00", "8.00"));

    // act
    var response = service.findOrders(new RequestContext("alice"), new FindOrdersRequest(20, null));

    // assert
    assertThat(response.nextContinuation()).isNull();
    assertThat(response.orders())
        .containsExactly(
            new OrderSummary(
                "2",
                OrderState.TO_PICK,
                1700000100,
                DeliveryMode.PICKUP,
                "8.00",
                "8.00",
                "8.00",
                1,
                List.of(new OrderSummaryRow("sku-c", "8.00"))),
            new OrderSummary(
                "1",
                OrderState.FULFILLED,
                1700000000,
                DeliveryMode.DELIVERY,
                "19.00",
                "14.50",
                null,
                3,
                List.of(
                    new OrderSummaryRow("sku-a", "10.00"), new OrderSummaryRow("sku-b", "4.50"))));
  }

  @Test
  void findOrdersShouldFollowContinuationPages() throws Exception {
    // arrange
    orderTable.putItem(order("alice", "1", "fulfilled", null, null, 0, 1));
    orderTable.putItem(order("alice", "2", "fulfilled", null, null, 0, 2));
    orderTable.putItem(order("alice", "3", "fulfilled", null, null, 0, 3));

    // act
    var first = service.findOrders(new RequestContext("alice"), new FindOrdersRequest(2, null));
    var second =
        service.findOrders(
            new RequestContext("alice"), new FindOrdersRequest(2, first.nextContinuation()));

    // assert
    assertThat(first.orders()).extracting(OrderSummary::orderId).containsExactly("3", "2");
    assertThat(first.nextContinuation()).isNotBlank();
    assertThat(second.orders()).extracting(OrderSummary::orderId).containsExactly("1");
    assertThat(second.nextContinuation()).isNull();
  }

  @Test
  void invalidLimitsShouldFailBeforeStorageAccess() {
    var repository = mock(OrderReadRepository.class);
    var invalidService = new FindOrdersService(repository, objectMapper);

    assertThatThrownBy(
            () ->
                invalidService.findOrders(
                    new RequestContext("alice"), new FindOrdersRequest(0, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("limit must be between 1 and 100");
    assertThatThrownBy(
            () ->
                invalidService.findOrders(
                    new RequestContext("alice"), new FindOrdersRequest(101, null)))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void incompleteRowDataShouldFailVisibly() {
    orderTable.putItem(order("alice", "1", "fulfilled", null, null, 1, 1));

    assertThatThrownBy(
            () ->
                service.findOrders(new RequestContext("alice"), new FindOrdersRequest(null, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("order rows are incomplete");
  }

  @Test
  void unknownStoredOrderStateFailsVisibly() {
    orderTable.putItem(order("alice", "1", "future-state", null, null, 0, 1));

    assertThatThrownBy(
            () ->
                service.findOrders(new RequestContext("alice"), new FindOrdersRequest(null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void malformedContinuationReturnsSafeBadRequest() {
    assertThatThrownBy(
            () ->
                service.findOrders(
                    new RequestContext("alice"), new FindOrdersRequest(20, "not a token")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("Invalid continuation");
  }

  @Test
  void orderIdSortKeyKeepsExistingPaddingAndAcceptsOpaqueIds() {
    assertThat(OrderItem.formatSk("42")).isEqualTo("ORDER#00000000000000000042");
    assertThat(OrderItem.formatSk("offer-abc")).isEqualTo("ORDER#00000000000offer-abc");
  }

  @Test
  void requestAndResponseShouldUseSnakeCaseAndTypedEnumValues() throws Exception {
    var request = new FindOrdersRequest(10, "opaque");
    var response =
        new FindOrdersResponse(
            List.of(
                new OrderSummary(
                    "42",
                    OrderState.AWAITING_PAYMENT,
                    1700000000,
                    DeliveryMode.PICKUP,
                    null,
                    null,
                    null,
                    0,
                    List.of())),
            null);

    var requestJson = objectMapper.writeValueAsString(request);
    var responseJson = objectMapper.writeValueAsString(response);

    assertThat(requestJson).isEqualTo("{\"limit\":10,\"continuation\":\"opaque\"}");
    assertThat(responseJson)
        .isEqualTo(
            "{\"orders\":[{\"order_id\":\"42\",\"state\":\"awaiting_payment\","
                + "\"accepted_at\":1700000000,\"delivery_mode\":\"PICKUP\","
                + "\"total_price\":null,\"items_total_price\":null,\"listed_total_price\":null,"
                + "\"unit_count\":0,\"rows\":[]}],\"next_continuation\":null}");
    assertThat(objectMapper.readValue(requestJson, FindOrdersRequest.class)).isEqualTo(request);
  }

  @Test
  void getOrderShouldResolveLocationsOnlyWhileToPick() throws Exception {
    orderTable.putItem(order("alice", "42", "to_pick", "PICKUP", "3.00", 1, 1700000000));
    rowTable.putItem(row("alice", "42", 0, "sku-a", 1, "3.00", "3.50", List.of(7)));
    var sku = sku("sku-a");
    when(inventoryService.findUnitLocations(
            new RequestContext("alice"),
            new FindUnitLocationsRequest(List.of(new UnitReference("sku-a", 7)))))
        .thenReturn(
            new FindUnitLocationsResponse(
                List.of(new UnitLocation(sku, 7, "A0-7", "A0-6", null, null))));

    var response = getOrderService.getOrder(new RequestContext("alice"), new GetOrderRequest("42"));

    assertThat(response.units())
        .containsExactly(
            new OrderUnit(
                "mtg", 7, "A0-7", "A0-6", "card-a", "Card A", "set", "1", "normal", "NM", "3.00",
                null, null));
  }

  @Test
  void getOrderShouldReturnAllocatedCardsWithoutLocationsOutsideToPick() throws Exception {
    orderTable.putItem(order("alice", "42", "fulfilled", "PICKUP", "3.00", 1, 1700000000));
    rowTable.putItem(row("alice", "42", 0, "sku-a", 1, "3.00", "3.50", List.of(7)));
    var sku = sku("sku-a");
    when(inventoryService.getSku(new RequestContext("alice"), new GetSkuRequest("sku-a")))
        .thenReturn(new GetSkuResponse(sku));

    var response = getOrderService.getOrder(new RequestContext("alice"), new GetOrderRequest("42"));

    assertThat(response.unitCount()).isEqualTo(1);
    assertThat(response.units()).hasSize(1);
    assertThat(response.units().get(0).name()).isEqualTo("Card A");
    assertThat(response.units().get(0).location()).isNull();
    assertThat(response.units().get(0).currentLocation()).isNull();
    assertThat(response.units().get(0).previousCard()).isNull();
    assertThat(response.units().get(0).nextCard()).isNull();
    verifyNoInteractionsExceptGetSku(inventoryService);
  }

  @Test
  void missingPickingLocationShouldFailTheWholeDetailRead() throws Exception {
    orderTable.putItem(order("alice", "42", "to_pick", "PICKUP", "3.00", 1, 1700000000));
    rowTable.putItem(row("alice", "42", 0, "sku-a", 1, "3.00", "3.50", List.of(7)));
    when(inventoryService.findUnitLocations(
            new RequestContext("alice"),
            new FindUnitLocationsRequest(List.of(new UnitReference("sku-a", 7)))))
        .thenReturn(new FindUnitLocationsResponse(List.of()));

    assertThatThrownBy(
            () -> getOrderService.getOrder(new RequestContext("alice"), new GetOrderRequest("42")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("order allocation has no physical location");
  }

  private static Sku sku(String id) {
    return new Sku(
        id, "mtg", "card-a", "Card A", "set", "Set", "1", "normal", Condition.NM, null, null, 1, 1);
  }

  private static void verifyNoInteractionsExceptGetSku(InventoryService inventoryService)
      throws Exception {
    verify(inventoryService, never()).findUnitLocations(any(), any());
  }

  private static OrderItem order(
      String user,
      String orderId,
      String status,
      String deliveryMode,
      String totalPrice,
      int rowCount,
      long createdAt) {
    var item = new OrderItem();
    item.setPk(OrderItem.formatPk(user));
    item.setSk(OrderItem.formatSk(orderId));
    item.setOrderId(orderId);
    item.setStatus(status);
    item.setDeliveryMode(deliveryMode);
    item.setTotalPrice(totalPrice);
    item.setRowCount(rowCount);
    item.setCreatedAt(Instant.ofEpochSecond(createdAt));
    return item;
  }

  private static OrderRowItem row(
      String user,
      String orderId,
      int position,
      String skuId,
      int quantity,
      String price,
      String listedPrice) {
    return row(user, orderId, position, skuId, quantity, price, listedPrice, List.of());
  }

  private static OrderRowItem row(
      String user,
      String orderId,
      int position,
      String skuId,
      int quantity,
      String price,
      String listedPrice,
      List<Integer> allocatedSequenceNumbers) {
    var item = new OrderRowItem();
    item.setPk(OrderRowItem.formatPk(user, orderId));
    item.setSk(OrderRowItem.formatSk(position));
    item.setUser(user);
    item.setOrderId(orderId);
    item.setPosition(position);
    item.setSkuId(skuId);
    item.setQuantity(quantity);
    item.setPrice(price);
    item.setListedPrice(listedPrice);
    item.setAllocatedSequenceNumbers(allocatedSequenceNumbers);
    return item;
  }
}
