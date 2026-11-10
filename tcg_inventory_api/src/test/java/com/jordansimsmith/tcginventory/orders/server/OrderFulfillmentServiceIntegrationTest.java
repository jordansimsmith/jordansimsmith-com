package com.jordansimsmith.tcginventory.orders.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
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
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

@Testcontainers
class OrderFulfillmentServiceIntegrationTest {
  private static final String TABLE_NAME = "tcg_inventory";
  private static final String USER = "orders-fulfillment-user";
  private static final String ORDER_ID = "order-opaque-id";
  private static final String SKU_ID = "mtg#scryfall#card-1#normal#NM";
  private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private TcgInventoryTestFactory factory;
  private FakeClock clock;
  private InventoryService inventoryService;
  private DynamoDbTable<OrderItem> orderTable;
  private DynamoDbTable<OrderRowItem> rowTable;
  private OrderReadRepository orderReadRepository;
  private OrderMutationRepository orderMutationRepository;

  @BeforeAll
  static void createTable() {
    var factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
  }

  @BeforeEach
  void setUp() {
    factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    clock = factory.fakeClock();
    clock.setTime(NOW);
    inventoryService = factory.inventoryService();
    DynamoDbUtils.reset(factory.dynamoDbClient());

    var enhanced = factory.dynamoDbEnhancedClient();
    orderTable = enhanced.table(TABLE_NAME, TableSchema.fromBean(OrderItem.class));
    rowTable = enhanced.table(TABLE_NAME, TableSchema.fromBean(OrderRowItem.class));
    orderReadRepository = new OrderReadRepository(orderTable, rowTable);
    orderMutationRepository =
        new OrderMutationRepository(
            orderTable, rowTable, factory.dynamoDbClient(), TABLE_NAME, clock);
  }

  @Test
  void lifecycleWritesShouldBeConditionalAndPreserveOrderAttributes() {
    var order = createOrder("to_pick", List.of());
    orderTable.putItem(order);
    clock.setTime(NOW.plusSeconds(10));

    orderMutationRepository.startFulfilling(USER, ORDER_ID);

    var fulfilling = orderReadRepository.getOrder(USER, ORDER_ID);
    assertThat(fulfilling.getStatus()).isEqualTo("fulfilling");
    assertThat(fulfilling.getUpdatedAt()).isEqualTo(NOW.plusSeconds(10));
    assertThat(fulfilling.getBuyerName()).isEqualTo("Buyer");
    assertThat(fulfilling.getPostageOption()).isEqualTo("Courier");

    clock.setTime(NOW.plusSeconds(20));
    orderMutationRepository.finishFulfilling(USER, ORDER_ID);

    var fulfilled = orderReadRepository.getOrder(USER, ORDER_ID);
    assertThat(fulfilled.getStatus()).isEqualTo("fulfilled");
    assertThat(fulfilled.getUpdatedAt()).isEqualTo(NOW.plusSeconds(20));
    assertThat(fulfilled.getBuyerName()).isEqualTo("Buyer");
    assertThatThrownBy(() -> orderMutationRepository.startFulfilling(USER, ORDER_ID))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThatThrownBy(() -> orderMutationRepository.finishFulfilling(USER, ORDER_ID))
        .isInstanceOf(ConditionalCheckFailedException.class);
  }

  @Test
  void fulfillmentShouldSellAllSavedUnitsAndCompleteOrdersLargerThanOneHundred() throws Exception {
    var sequences = sequences(101);
    seedReservedUnits(sequences);
    createFulfillingOrder(List.of(sequences));
    var handler = createHandler(inventoryService);

    handler.handleMessage(new RequestContext(USER), message());

    assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo("fulfilled");
    assertThat(findUnits())
        .hasSize(101)
        .allSatisfy(
            unit -> {
              assertThat(unit.status()).isEqualTo(UnitStatus.SOLD);
              assertThat(unit.reservationId()).isEqualTo(ORDER_ID);
            });
    assertThat(
            factory
                .inventoryService()
                .getSku(new RequestContext(USER), new GetSkuRequest(SKU_ID))
                .sku()
                .version())
        .isEqualTo(101);
    assertThat(
            factory
                .inventoryService()
                .findSkus(
                    new RequestContext(USER), new FindSkusRequest(null, null, true, 100, null))
                .skus())
        .isEmpty();
  }

  @Test
  void alreadyFulfilledReplayShouldNotReadRowsOrCallInventory() throws Exception {
    orderTable.putItem(createOrder("fulfilled", List.of()));
    var readRepository = mock(OrderReadRepository.class);
    when(readRepository.getOrder(USER, ORDER_ID))
        .thenReturn(orderReadRepository.getOrder(USER, ORDER_ID));
    var inventory = mock(InventoryService.class);

    createHandler(readRepository, orderMutationRepository, inventory)
        .handleMessage(new RequestContext(USER), message());

    verify(readRepository, never()).findRows(USER, orderReadRepository.getOrder(USER, ORDER_ID));
    verifyNoInteractions(inventory);
  }

  @Test
  void retryAfterCommittedSaleShouldNotDuplicateEarlierInventoryEffects() throws Exception {
    seedReservedUnits(List.of(1, 2));
    createFulfillingOrder(List.of(List.of(1, 2)));
    var inventory = spy(factory.fakeInventoryService());
    doAnswer(
            invocation -> {
              var response = invocation.callRealMethod();
              var request = invocation.getArgument(1, SellUnitRequest.class);
              if (request.sequenceNumber() == 1) {
                throw new IllegalStateException("sale response lost");
              }
              return response;
            })
        .when(inventory)
        .sellUnit(any(), any());
    var handler = createHandler(inventory);

    assertThatThrownBy(() -> handler.handleMessage(new RequestContext(USER), message()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("sale response lost");
    assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo("fulfilling");
    assertThat(findUnits())
        .extracting(Unit::status)
        .containsExactly(UnitStatus.SOLD, UnitStatus.RESERVED);
    assertThat(
            factory
                .inventoryService()
                .getSku(new RequestContext(USER), new GetSkuRequest(SKU_ID))
                .sku()
                .version())
        .isEqualTo(1);

    createHandler(factory.inventoryService()).handleMessage(new RequestContext(USER), message());

    assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo("fulfilled");
    assertThat(findUnits()).extracting(Unit::status).containsOnly(UnitStatus.SOLD);
    assertThat(
            factory
                .inventoryService()
                .getSku(new RequestContext(USER), new GetSkuRequest(SKU_ID))
                .sku()
                .version())
        .isEqualTo(2);
  }

  @Test
  void retryAfterOrderCompletionWriteFailureShouldNotRepeatSales() throws Exception {
    seedReservedUnits(List.of(1));
    createFulfillingOrder(List.of(List.of(1)));
    var mutationRepository = spy(orderMutationRepository);
    doThrow(new IllegalStateException("order write unavailable"))
        .doCallRealMethod()
        .when(mutationRepository)
        .finishFulfilling(USER, ORDER_ID);
    var handler = createHandler(orderReadRepository, mutationRepository, inventoryService);

    assertThatThrownBy(() -> handler.handleMessage(new RequestContext(USER), message()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("order write unavailable");
    var versionAfterSale =
        factory
            .inventoryService()
            .getSku(new RequestContext(USER), new GetSkuRequest(SKU_ID))
            .sku()
            .version();
    assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo("fulfilling");

    handler.handleMessage(new RequestContext(USER), message());

    assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo("fulfilled");
    assertThat(
            factory
                .inventoryService()
                .getSku(new RequestContext(USER), new GetSkuRequest(SKU_ID))
                .sku()
                .version())
        .isEqualTo(versionAfterSale);
  }

  @Test
  void invalidAllocationsShouldFailBeforeAnySale() {
    seedReservedUnits(List.of(1));
    createFulfillingOrder(List.of(List.of(1, 1)));
    var inventory = mock(InventoryService.class);

    assertThatThrownBy(
            () -> createHandler(inventory).handleMessage(new RequestContext(USER), message()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("order allocation contains a duplicate unit");
    verifyNoInteractions(inventory);
    assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo("fulfilling");
  }

  @Test
  void missingOrderAndWrongStateShouldFailVisibly() {
    var handler = createHandler(inventoryService);

    assertThatThrownBy(() -> handler.handleMessage(new RequestContext(USER), message()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("fulfillment order not found");

    orderTable.putItem(createOrder("to_pick", List.of()));
    assertThatThrownBy(() -> handler.handleMessage(new RequestContext(USER), message()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("order is not fulfilling");
  }

  @Test
  void fulfillmentShouldNotReadAnotherUsersOrder() {
    orderTable.putItem(createOrder("another-user", ORDER_ID, "fulfilling", List.of()));

    assertThatThrownBy(
            () ->
                createHandler(inventoryService).handleMessage(new RequestContext(USER), message()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("fulfillment order not found");
  }

  @Test
  void emptyAllocationsShouldFailBeforeCallingInventory() {
    orderTable.putItem(createOrder("fulfilling", List.of()));
    var inventory = mock(InventoryService.class);

    assertThatThrownBy(
            () -> createHandler(inventory).handleMessage(new RequestContext(USER), message()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("order has no lines to fulfill");
    verifyNoInteractions(inventory);
  }

  @Test
  void fulfillmentShouldPropagateCheckedInventoryFailures() throws Exception {
    seedReservedUnits(List.of(1));
    createFulfillingOrder(List.of(List.of(1)));
    var inventory = mock(InventoryService.class);
    var failure = new ServiceUnavailableException("inventory unavailable");
    when(inventory.sellUnit(new RequestContext(USER), new SellUnitRequest(SKU_ID, 1, ORDER_ID)))
        .thenThrow(failure);

    assertThatThrownBy(
            () -> createHandler(inventory).handleMessage(new RequestContext(USER), message()))
        .isSameAs(failure);
    assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo("fulfilling");
  }

  @Test
  void fulfillmentShouldPropagateStorageFailureAfterSales() throws Exception {
    seedReservedUnits(List.of(1));
    createFulfillingOrder(List.of(List.of(1)));
    var mutationRepository = spy(orderMutationRepository);
    var failure = SdkClientException.create("dynamodb unavailable");
    doThrow(failure).when(mutationRepository).finishFulfilling(USER, ORDER_ID);

    assertThatThrownBy(
            () ->
                createHandler(orderReadRepository, mutationRepository, inventoryService)
                    .handleMessage(new RequestContext(USER), message()))
        .isSameAs(failure);
    assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo("fulfilling");
    assertThat(findUnits())
        .singleElement()
        .satisfies(unit -> assertThat(unit.status()).isEqualTo(UnitStatus.SOLD));
  }

  @Test
  void fulfillmentMessageShouldUseSnakeCaseJson() throws Exception {
    var mapper = new ObjectMapper();

    var json = mapper.writeValueAsString(message());

    assertThat(json)
        .isEqualTo("{\"user\":\"orders-fulfillment-user\",\"order_id\":\"order-opaque-id\"}");
    assertThat(mapper.readValue(json, FulfillmentMessage.class)).isEqualTo(message());
  }

  private void seedReservedUnits(List<Integer> sequences) {
    var fakeInventory = factory.fakeInventoryService();
    fakeInventory.seedSku(
        USER,
        new Sku(
            SKU_ID,
            "mtg",
            "card-1",
            "Card",
            "dom",
            "Dominaria",
            "1",
            "normal",
            Condition.NM,
            new BigDecimal("2.00"),
            "fetchtcg-card-1",
            2,
            0),
        false);
    fakeInventory.seedUnits(
        USER,
        SKU_ID,
        sequences.stream()
            .map(
                sequence ->
                    new Unit(
                        sequence,
                        "A0-" + sequence,
                        UnitStatus.RESERVED,
                        List.of(),
                        NOW.getEpochSecond(),
                        null,
                        ORDER_ID))
            .toList());
  }

  private void createFulfillingOrder(List<List<Integer>> allocationByRow) {
    var rows = new ArrayList<OrderRowItem>();
    for (var sequences : allocationByRow) {
      rows.add(
          OrderRowItem.create(
              USER,
              ORDER_ID,
              rows.size(),
              SKU_ID,
              101,
              sequences.size(),
              "2.00",
              "2.00",
              sequences));
    }
    orderTable.putItem(createOrder("fulfilling", rows));
    rows.forEach(rowTable::putItem);
  }

  private OrderItem createOrder(String status, List<OrderRowItem> rows) {
    return createOrder(USER, ORDER_ID, status, rows);
  }

  private OrderItem createOrder(
      String user, String orderId, String status, List<OrderRowItem> rows) {
    return OrderItem.create(
        user,
        orderId,
        status,
        "ACCEPTED",
        null,
        "PICKUP",
        "Buyer",
        null,
        "Courier",
        "2.00",
        rows.size(),
        NOW);
  }

  private List<Integer> sequences(int count) {
    var sequences = new ArrayList<Integer>();
    for (int sequence = 1; sequence <= count; sequence++) {
      sequences.add(sequence);
    }
    return sequences;
  }

  private List<Unit> findUnits() throws Exception {
    var units = new ArrayList<Unit>();
    String continuation = null;
    do {
      var response =
          factory
              .inventoryService()
              .findUnits(new RequestContext(USER), new FindUnitsRequest(SKU_ID, 100, continuation));
      units.addAll(response.units());
      continuation = response.nextContinuation();
    } while (continuation != null);
    return units;
  }

  private FulfillmentMessageHandler createHandler(InventoryService inventory) {
    return createHandler(orderReadRepository, orderMutationRepository, inventory);
  }

  private FulfillmentMessageHandler createHandler(
      OrderReadRepository readRepository,
      OrderMutationRepository mutationRepository,
      InventoryService inventory) {
    return new FulfillmentMessageHandler(readRepository, mutationRepository, inventory);
  }

  private static FulfillmentMessage message() {
    return new FulfillmentMessage(USER, ORDER_ID);
  }
}
