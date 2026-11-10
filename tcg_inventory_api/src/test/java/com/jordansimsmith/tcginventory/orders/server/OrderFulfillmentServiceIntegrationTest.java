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
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetUnitResponse;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.SellUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.orders.client.ConfirmOrderRequest;
import com.jordansimsmith.tcginventory.orders.client.ConfirmOrderResponse;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
  void fulfillmentMessageShouldUseSnakeCaseJson() throws Exception {
    var mapper = new ObjectMapper();

    var json = mapper.writeValueAsString(message());

    assertThat(json)
        .isEqualTo("{\"user\":\"orders-fulfillment-user\",\"order_id\":\"order-opaque-id\"}");
    assertThat(mapper.readValue(json, FulfillmentMessage.class)).isEqualTo(message());
  }

  @Test
  void confirmOrderShouldAdmitAndDispatchBeforeFulfillment() throws Exception {
    // arrange
    seedReservedUnits(List.of(2, 1));
    createAllocatedOrder("to_pick", List.of(List.of(2, 1)));
    clock.setTime(NOW.plusSeconds(10));

    // act
    var response =
        factory
            .orderService()
            .confirmOrder(new RequestContext(USER), new ConfirmOrderRequest(ORDER_ID));

    // assert
    assertThat(response).isEqualTo(new ConfirmOrderResponse());
    var order = orderReadRepository.getOrder(USER, ORDER_ID);
    assertThat(order.getStatus()).isEqualTo("fulfilling");
    assertThat(order.getUpdatedAt()).isEqualTo(NOW.plusSeconds(10));
    assertThat(order.getBuyerName()).isEqualTo("Buyer");
    assertThat(order.getPostageOption()).isEqualTo("Courier");
    assertThat(findUnits()).extracting(Unit::status).containsOnly(UnitStatus.RESERVED);
    assertThat(factory.fakeOrdersQueue().getSends())
        .containsExactly(new FakeQueueClient.SentMessage<>(message(), ORDER_ID, ORDER_ID));

    factory
        .fulfillmentMessageHandler()
        .handleMessage(
            new RequestContext(USER), factory.fakeOrdersQueue().getMessages().getFirst());
    assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo("fulfilled");
    assertThat(findUnits()).extracting(Unit::status).containsOnly(UnitStatus.SOLD);
  }

  @Test
  void confirmOrderShouldReturnWithoutDownstreamWorkForAdmittedOrCompletedOrders()
      throws Exception {
    // arrange
    var readRepository = spy(orderReadRepository);
    var mutationRepository = mock(OrderMutationRepository.class);
    var inventory = mock(InventoryService.class);
    var queue = new FakeQueueClient<FulfillmentMessage>();
    var service = new ConfirmOrderService(readRepository, mutationRepository, inventory, queue);

    // act
    for (var status : List.of("fulfilling", "fulfilled")) {
      orderTable.putItem(createOrder(status, List.of()));
      assertThat(service.confirmOrder(new RequestContext(USER), new ConfirmOrderRequest(ORDER_ID)))
          .isEqualTo(new ConfirmOrderResponse());
    }

    // assert
    verify(readRepository, never()).findRows(any(), any());
    verifyNoInteractions(mutationRepository, inventory);
    assertThat(queue.getSends()).isEmpty();
  }

  @Test
  void confirmOrderShouldValidateIdsBeforeStorageAndPreserveOpaqueValues() throws Exception {
    // arrange
    var readRepository = mock(OrderReadRepository.class);
    var mutationRepository = mock(OrderMutationRepository.class);
    var inventory = mock(InventoryService.class);
    var queue = new FakeQueueClient<FulfillmentMessage>();
    var service = new ConfirmOrderService(readRepository, mutationRepository, inventory, queue);

    // act
    for (var id : Arrays.asList(null, "", "  ")) {
      assertThatThrownBy(
              () -> service.confirmOrder(new RequestContext(USER), new ConfirmOrderRequest(id)))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("order_id is required");
    }

    // assert
    verifyNoInteractions(readRepository, mutationRepository, inventory);
    var opaqueId = " offer:# / ";
    orderTable.putItem(createOrder(USER, opaqueId, "fulfilled", List.of()));
    assertThat(
            factory
                .orderService()
                .confirmOrder(new RequestContext(USER), new ConfirmOrderRequest(opaqueId)))
        .isEqualTo(new ConfirmOrderResponse());
    assertThat(queue.getSends()).isEmpty();
  }

  @Test
  void confirmOrderShouldRejectMissingForeignAndUnreadyOrders() {
    // arrange
    var service =
        new ConfirmOrderService(
            orderReadRepository,
            orderMutationRepository,
            inventoryService,
            factory.fakeOrdersQueue());
    orderTable.putItem(createOrder("another-user", ORDER_ID, "to_pick", List.of()));

    // act
    assertThatThrownBy(
            () -> service.confirmOrder(new RequestContext(USER), new ConfirmOrderRequest(ORDER_ID)))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");
    for (var status : List.of("reserving", "awaiting_payment", "voiding", "voided")) {
      orderTable.putItem(createOrder(status, List.of()));
      assertThatThrownBy(
              () ->
                  service.confirmOrder(new RequestContext(USER), new ConfirmOrderRequest(ORDER_ID)))
          .isInstanceOf(ConflictException.class)
          .hasMessage("order is not ready to pick");
      assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo(status);
    }

    // assert
    assertThat(factory.fakeOrdersQueue().getSends()).isEmpty();
  }

  @Test
  void confirmOrderShouldValidateAllAllocationsBeforeInventoryReads() {
    // arrange
    var inventory = mock(InventoryService.class);
    var service =
        new ConfirmOrderService(
            orderReadRepository, orderMutationRepository, inventory, factory.fakeOrdersQueue());

    // act
    createAllocatedOrder("to_pick", List.of());
    assertThatThrownBy(
            () -> service.confirmOrder(new RequestContext(USER), new ConfirmOrderRequest(ORDER_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("order has no lines to fulfill");
    createAllocatedOrder("to_pick", List.of(List.of(1), List.of(1)));
    assertThatThrownBy(
            () -> service.confirmOrder(new RequestContext(USER), new ConfirmOrderRequest(ORDER_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("order allocation contains a duplicate unit");
    for (var quantity : List.of(0, 2)) {
      rowTable.putItem(
          OrderRowItem.create(
              USER, ORDER_ID, 1, SKU_ID, 101, quantity, "2.00", "2.00", List.of(2)));
      assertThatThrownBy(
              () ->
                  service.confirmOrder(new RequestContext(USER), new ConfirmOrderRequest(ORDER_ID)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("order line is not fully allocated");
    }

    // assert
    verifyNoInteractions(inventory);
    assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo("to_pick");
    assertThat(factory.fakeOrdersQueue().getSends()).isEmpty();
  }

  @Test
  void confirmOrderShouldRejectUnexpectedInventoryOwnership() throws Exception {
    // arrange
    createAllocatedOrder("to_pick", List.of(List.of(1)));
    var inventory = mock(InventoryService.class);
    var ctx = new RequestContext(USER);
    var request = new GetUnitRequest(SKU_ID, 1);
    var service =
        new ConfirmOrderService(
            orderReadRepository, orderMutationRepository, inventory, factory.fakeOrdersQueue());

    // act
    for (var unit :
        List.of(
            new Unit(1, "A0-1", UnitStatus.IN_STOCK, List.of(), NOW.getEpochSecond(), null, null),
            new Unit(
                1,
                "A0-1",
                UnitStatus.RESERVED,
                List.of(),
                NOW.getEpochSecond(),
                null,
                "other-order"))) {
      when(inventory.getUnit(ctx, request)).thenReturn(new GetUnitResponse(unit));
      assertThatThrownBy(() -> service.confirmOrder(ctx, new ConfirmOrderRequest(ORDER_ID)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("order has an unexpected allocated unit");
    }

    // assert
    assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo("to_pick");
    assertThat(factory.fakeOrdersQueue().getSends()).isEmpty();
  }

  @Test
  void confirmOrderShouldNotDispatchWhenAdmissionFails() {
    // arrange
    seedReservedUnits(List.of(1));
    createAllocatedOrder("to_pick", List.of(List.of(1)));
    var mutationRepository = spy(orderMutationRepository);
    var failure = ConditionalCheckFailedException.builder().message("admission lost").build();
    doThrow(failure).when(mutationRepository).startFulfilling(USER, ORDER_ID);
    var service =
        new ConfirmOrderService(
            orderReadRepository, mutationRepository, inventoryService, factory.fakeOrdersQueue());

    // act
    assertThatThrownBy(
            () -> service.confirmOrder(new RequestContext(USER), new ConfirmOrderRequest(ORDER_ID)))
        .isSameAs(failure);

    // assert
    assertThat(factory.fakeOrdersQueue().getSends()).isEmpty();
    assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo("to_pick");
  }

  @Test
  void confirmOrderShouldRetainAdmissionWithoutResendingAfterQueueFailure() throws Exception {
    // arrange
    seedReservedUnits(List.of(1));
    createAllocatedOrder("to_pick", List.of(List.of(1)));
    var queue = spy(new FakeQueueClient<FulfillmentMessage>());
    var failure = SdkClientException.create("sqs unavailable");
    doThrow(failure).when(queue).send(message(), ORDER_ID, ORDER_ID);
    var service =
        new ConfirmOrderService(
            orderReadRepository, orderMutationRepository, inventoryService, queue);

    // act
    assertThatThrownBy(
            () -> service.confirmOrder(new RequestContext(USER), new ConfirmOrderRequest(ORDER_ID)))
        .isSameAs(failure);
    assertThat(service.confirmOrder(new RequestContext(USER), new ConfirmOrderRequest(ORDER_ID)))
        .isEqualTo(new ConfirmOrderResponse());

    // assert
    assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo("fulfilling");
    verify(queue).send(message(), ORDER_ID, ORDER_ID);
  }

  @Test
  void confirmOrderShouldPropagateDependencyFailuresWithoutAdmission() throws Exception {
    // arrange
    createAllocatedOrder("to_pick", List.of(List.of(1)));
    var inventory = mock(InventoryService.class);
    var failure = new ServiceUnavailableException("inventory unavailable");
    when(inventory.getUnit(new RequestContext(USER), new GetUnitRequest(SKU_ID, 1)))
        .thenThrow(failure);
    var service =
        new ConfirmOrderService(
            orderReadRepository, orderMutationRepository, inventory, factory.fakeOrdersQueue());

    // act
    assertThatThrownBy(
            () -> service.confirmOrder(new RequestContext(USER), new ConfirmOrderRequest(ORDER_ID)))
        .isSameAs(failure);

    var readRepository = spy(orderReadRepository);
    var storageFailure = SdkClientException.create("dynamodb unavailable");
    doThrow(storageFailure).when(readRepository).getOrder(USER, ORDER_ID);
    var unavailableService =
        new ConfirmOrderService(
            readRepository, orderMutationRepository, inventory, factory.fakeOrdersQueue());
    assertThatThrownBy(
            () ->
                unavailableService.confirmOrder(
                    new RequestContext(USER), new ConfirmOrderRequest(ORDER_ID)))
        .isSameAs(storageFailure);

    // assert
    assertThat(orderReadRepository.getOrder(USER, ORDER_ID).getStatus()).isEqualTo("to_pick");
    assertThat(factory.fakeOrdersQueue().getSends()).isEmpty();
  }

  @Test
  void confirmationRecordsShouldUseOrderIdAndEmptyResponseJson() throws Exception {
    // arrange
    var mapper = new ObjectMapper();
    var request = new ConfirmOrderRequest(ORDER_ID);

    // act
    var json = mapper.writeValueAsString(request);

    // assert
    assertThat(json).isEqualTo("{\"order_id\":\"order-opaque-id\"}");
    assertThat(mapper.readValue(json, ConfirmOrderRequest.class)).isEqualTo(request);
    assertThat(mapper.writeValueAsString(new ConfirmOrderResponse())).isEqualTo("{}");
    assertThat(mapper.readValue("{}", ConfirmOrderResponse.class))
        .isEqualTo(new ConfirmOrderResponse());
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
    createAllocatedOrder("fulfilling", allocationByRow);
  }

  private void createAllocatedOrder(String status, List<List<Integer>> allocationByRow) {
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
    orderTable.putItem(createOrder(status, rows));
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
