package com.jordansimsmith.tcginventory.publish.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FakeInventoryService;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusResponse;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.listings.client.FakeListingService;
import com.jordansimsmith.tcginventory.listings.client.FindListingsRequest;
import com.jordansimsmith.tcginventory.listings.client.Listing;
import com.jordansimsmith.tcginventory.listings.client.ListingService;
import com.jordansimsmith.tcginventory.listings.client.UpdateListingRequest;
import com.jordansimsmith.tcginventory.listings.client.UpdateListingResponse;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrdersRequest;
import com.jordansimsmith.tcginventory.publish.client.CreatePublishRequest;
import com.jordansimsmith.tcginventory.publish.client.CreatePublishResponse;
import com.jordansimsmith.tcginventory.publish.client.GetPublishRequest;
import com.jordansimsmith.tcginventory.publish.client.GetPublishResponse;
import com.jordansimsmith.tcginventory.publish.client.PublishStatus;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@Testcontainers
class PublishServiceIntegrationTest {
  private static final String TABLE_NAME = "publish_test";
  private static final String USER = "alice";
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<PublishItem> publishTable;
  private PublishRepository publishRepository;
  private FakeClock clock;
  private FakeUlidGenerator ulidGenerator;

  @BeforeAll
  static void setUpBeforeClass() {
    dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    publishTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(PublishItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, publishTable);
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    publishRepository = new PublishRepository(publishTable, dynamoDbClient, TABLE_NAME);
    clock = new FakeClock();
    clock.setTime(Instant.ofEpochSecond(1_700_000_000L));
    ulidGenerator = new FakeUlidGenerator();
  }

  @Test
  void lifecycleShouldPersistKeysTimesAndNumericProgress() {
    var startedAt = Instant.ofEpochSecond(1_700_000_001L);
    var runningAt = Instant.ofEpochSecond(1_700_000_002L);
    var checkpointAt = Instant.ofEpochSecond(1_700_000_003L);
    var finishedAt = Instant.ofEpochSecond(1_700_000_004L);

    publishRepository.createPublish(USER, "publish-1", startedAt);
    var queued = publishRepository.get(USER);
    publishRepository.startPublish(USER, "publish-1", runningAt);
    publishRepository.checkpointPublish(USER, "publish-1", 0, 7, checkpointAt);
    publishRepository.completePublish(USER, "publish-1", 7, 7, finishedAt);
    publishRepository.createPublish("bob", "bob-publish", Instant.ofEpochSecond(1_700_000_005L));

    assertThat(queued.getUser()).isEqualTo(USER);
    assertThat(queued.getStatus()).isEqualTo("queued");
    assertThat(queued.getPublishedSkuCount()).isZero();
    assertThat(queued.getStartedAt()).isEqualTo(startedAt);
    assertThat(publishRepository.get("bob").getPublishId()).isEqualTo("bob-publish");
    var completed = publishRepository.get(USER);
    assertThat(completed.getStatus()).isEqualTo("succeeded");
    assertThat(completed.getPublishId()).isEqualTo("publish-1");
    assertThat(completed.getPublishedSkuCount()).isEqualTo(7);
    assertThat(completed.getStartedAt()).isEqualTo(startedAt);
    assertThat(completed.getUpdatedAt()).isEqualTo(finishedAt);
    assertThat(completed.getFinishedAt()).isEqualTo(finishedAt);
    assertThat(getRaw(USER))
        .containsEntry(PublishItem.PK, AttributeValue.fromS("USER#alice"))
        .containsEntry(PublishItem.SK, AttributeValue.fromS("PUBLISH"))
        .containsEntry(PublishItem.PUBLISHED_SKU_COUNT, AttributeValue.fromN("7"))
        .containsEntry(PublishItem.STARTED_AT, AttributeValue.fromN("1700000001"))
        .containsEntry(PublishItem.UPDATED_AT, AttributeValue.fromN("1700000004"))
        .containsEntry(PublishItem.FINISHED_AT, AttributeValue.fromN("1700000004"))
        .doesNotContainKey("continuation");
  }

  @Test
  void createShouldRejectActiveRunsAndReplaceSucceededRunPreservingOtherAttributes() {
    publishRepository.createPublish(USER, "publish-1", Instant.ofEpochSecond(1));
    assertThatThrownBy(
            () -> publishRepository.createPublish(USER, "publish-2", Instant.ofEpochSecond(2)))
        .isInstanceOf(ConditionalCheckFailedException.class);

    publishRepository.startPublish(USER, "publish-1", Instant.ofEpochSecond(2));
    assertThatThrownBy(
            () -> publishRepository.createPublish(USER, "publish-2", Instant.ofEpochSecond(3)))
        .isInstanceOf(ConditionalCheckFailedException.class);
    publishRepository.checkpointPublish(USER, "publish-1", 0, 3, Instant.ofEpochSecond(3));
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(TABLE_NAME)
            .key(key(USER))
            .updateExpression("SET #other = :value")
            .expressionAttributeNames(Map.of("#other", "other_attribute"))
            .expressionAttributeValues(Map.of(":value", AttributeValue.fromS("preserved")))
            .build());
    publishRepository.completePublish(USER, "publish-1", 3, 3, Instant.ofEpochSecond(4));

    publishRepository.createPublish(USER, "publish-2", Instant.ofEpochSecond(5));

    assertThat(publishRepository.get(USER).getPublishId()).isEqualTo("publish-2");
    assertThat(publishRepository.get(USER).getStatus()).isEqualTo("queued");
    assertThat(publishRepository.get(USER).getPublishedSkuCount()).isZero();
    assertThat(publishRepository.get(USER).getStartedAt()).isEqualTo(Instant.ofEpochSecond(5));
    assertThat(publishRepository.get(USER).getFinishedAt()).isNull();
    assertThat(getRaw(USER)).doesNotContainKey("continuation");
    assertThat(getRaw(USER)).containsEntry("other_attribute", AttributeValue.fromS("preserved"));
  }

  @Test
  void writesShouldRejectStaleIdsCountsAndInvalidTransitions() {
    publishRepository.createPublish(USER, "publish-1", Instant.ofEpochSecond(1));

    assertThatThrownBy(
            () -> publishRepository.startPublish(USER, "stale-id", Instant.ofEpochSecond(2)))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThatThrownBy(
            () -> publishRepository.checkpointPublish(USER, "publish-1", 0, 1, Instant.now()))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThatThrownBy(
            () -> publishRepository.completePublish(USER, "publish-1", 0, 0, Instant.now()))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThatThrownBy(
            () -> publishRepository.checkpointPublish(USER, "publish-1", 0, 0, Instant.now()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("checkpoint must advance published_sku_count");

    publishRepository.startPublish(USER, "publish-1", Instant.ofEpochSecond(2));
    assertThatThrownBy(
            () -> publishRepository.checkpointPublish(USER, "publish-1", 1, 2, Instant.now()))
        .isInstanceOf(ConditionalCheckFailedException.class);
    publishRepository.checkpointPublish(USER, "publish-1", 0, 1, Instant.ofEpochSecond(3));
    assertThatThrownBy(
            () -> publishRepository.completePublish(USER, "publish-1", 0, 1, Instant.now()))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThatThrownBy(
            () -> publishRepository.completePublish(USER, "publish-1", 1, 0, Instant.now()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("published_sku_count cannot decrease");
  }

  @Test
  void emptyRunCanCompleteWithoutAdvancingCount() {
    publishRepository.createPublish(USER, "publish-1", Instant.ofEpochSecond(1));
    publishRepository.startPublish(USER, "publish-1", Instant.ofEpochSecond(2));

    publishRepository.completePublish(USER, "publish-1", 0, 0, Instant.ofEpochSecond(3));

    assertThat(publishRepository.get(USER).getStatus()).isEqualTo("succeeded");
    assertThat(publishRepository.get(USER).getPublishedSkuCount()).isZero();
  }

  @Test
  void repositoryPropagatesUnrelatedStorageFailures() {
    var failure = DynamoDbException.builder().message("storage unavailable").build();
    var client = mock(DynamoDbClient.class);
    when(client.updateItem(any(UpdateItemRequest.class))).thenThrow(failure);
    var repository = new PublishRepository(publishTable, client, TABLE_NAME);

    assertThatThrownBy(() -> repository.createPublish(USER, "publish-1", Instant.ofEpochSecond(1)))
        .isSameAs(failure);
  }

  @Test
  void clientTypesUseTheExpectedJsonShapes() throws Exception {
    var mapper = new ObjectMapper();

    assertThat(mapper.writeValueAsString(new CreatePublishRequest())).isEqualTo("{}");
    assertThat(mapper.writeValueAsString(new CreatePublishResponse())).isEqualTo("{}");
    assertThat(mapper.writeValueAsString(new GetPublishRequest())).isEqualTo("{}");
    assertThat(mapper.writeValueAsString(PublishStatus.QUEUED)).isEqualTo("\"queued\"");
    assertThat(mapper.readValue("\"running\"", PublishStatus.class))
        .isEqualTo(PublishStatus.RUNNING);
    var response = new GetPublishResponse(PublishStatus.SUCCEEDED, 4, 10L, null, 2);
    var json = mapper.writeValueAsString(response);
    assertThat(json)
        .isEqualTo(
            "{\"status\":\"succeeded\",\"published_sku_count\":4,"
                + "\"started_at\":10,\"finished_at\":null,\"pending_sku_count\":2}");
    assertThat(mapper.readValue(json, GetPublishResponse.class)).isEqualTo(response);
    assertThat(mapper.readValue("{}", GetPublishRequest.class)).isEqualTo(new GetPublishRequest());
  }

  @Test
  void createServiceShouldPersistAndDispatchOnceForAnActiveRun() throws Exception {
    var ctx = new RequestContext(USER);
    var queue = new FakeQueueClient<PublishMessage>();
    var service = new CreatePublishService(publishRepository, queue, clock, ulidGenerator);

    service.createPublish(ctx, new CreatePublishRequest());
    service.createPublish(ctx, new CreatePublishRequest());
    publishRepository.startPublish(USER, "FAKE_ULID_0000000001", clock.now());
    service.createPublish(ctx, new CreatePublishRequest());

    assertThat(publishRepository.get(USER).getPublishId()).isEqualTo("FAKE_ULID_0000000001");
    assertThat(publishRepository.get(USER).getStartedAt()).isEqualTo(clock.now());
    assertThat(queue.getSends())
        .containsExactly(
            new FakeQueueClient.SentMessage<>(
                new PublishMessage(USER, "FAKE_ULID_0000000001"), USER, "FAKE_ULID_0000000001#0"));
  }

  @Test
  void createServiceShouldAllowFreshRunAfterSuccess() throws Exception {
    var ctx = new RequestContext(USER);
    var queue = new FakeQueueClient<PublishMessage>();
    var service = new CreatePublishService(publishRepository, queue, clock, ulidGenerator);

    service.createPublish(ctx, new CreatePublishRequest());
    publishRepository.startPublish(USER, "FAKE_ULID_0000000001", clock.now());
    publishRepository.completePublish(USER, "FAKE_ULID_0000000001", 0, 0, clock.now());
    service.createPublish(ctx, new CreatePublishRequest());

    assertThat(publishRepository.get(USER).getPublishId()).isEqualTo("FAKE_ULID_0000000002");
    assertThat(queue.getMessages())
        .containsExactly(
            new PublishMessage(USER, "FAKE_ULID_0000000001"),
            new PublishMessage(USER, "FAKE_ULID_0000000002"));
  }

  @Test
  void createServiceShouldNotDispatchWhenAdmissionWriteFails() throws Exception {
    var ctx = new RequestContext(USER);
    var repository = mock(PublishRepository.class);
    when(repository.get(USER)).thenReturn(null);
    var failure = ConditionalCheckFailedException.builder().message("active run").build();
    doThrow(failure).when(repository).createPublish(eq(USER), any(), any());
    var queue = new FakeQueueClient<PublishMessage>();
    var service = new CreatePublishService(repository, queue, clock, ulidGenerator);

    assertThatThrownBy(() -> service.createPublish(ctx, new CreatePublishRequest()))
        .isSameAs(failure);

    assertThat(queue.getSends()).isEmpty();
  }

  @Test
  void createServiceShouldNotDispatchWhenStorageFails() throws Exception {
    var ctx = new RequestContext(USER);
    var repository = mock(PublishRepository.class);
    when(repository.get(USER)).thenReturn(null);
    var failure = DynamoDbException.builder().message("storage unavailable").build();
    doThrow(failure).when(repository).createPublish(eq(USER), any(), any());
    var queue = new FakeQueueClient<PublishMessage>();

    assertThatThrownBy(
            () ->
                new CreatePublishService(repository, queue, clock, ulidGenerator)
                    .createPublish(ctx, new CreatePublishRequest()))
        .isSameAs(failure);

    assertThat(queue.getSends()).isEmpty();
  }

  @Test
  void createServiceShouldLeaveQueuedRunWhenQueueSendFails() throws Exception {
    var ctx = new RequestContext(USER);
    var queue = mockQueue();
    var failure = new IllegalStateException("queue unavailable");
    doThrow(failure).when(queue).send(any(), eq(USER), eq("FAKE_ULID_0000000001#0"));
    var service = new CreatePublishService(publishRepository, queue, clock, ulidGenerator);

    assertThatThrownBy(() -> service.createPublish(ctx, new CreatePublishRequest()))
        .isSameAs(failure);
    assertThat(publishRepository.get(USER).getStatus()).isEqualTo("queued");

    service.createPublish(ctx, new CreatePublishRequest());

    verify(queue).send(any(), eq(USER), eq("FAKE_ULID_0000000001#0"));
  }

  @Test
  void getServiceShouldReturnNotFoundBeforeInventoryRead() throws Exception {
    var inventory = mock(InventoryService.class);
    var service = new GetPublishService(publishRepository, inventory);

    assertThatThrownBy(() -> service.getPublish(new RequestContext(USER), new GetPublishRequest()))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");

    verify(inventory, never()).findSkus(any(), any());
  }

  @Test
  void getServiceShouldCountAllDirtyPagesAndPreserveStatusTimes() throws Exception {
    var ctx = new RequestContext(USER);
    var inventory = mock(InventoryService.class);
    when(inventory.findSkus(ctx, new FindSkusRequest(null, null, true, 100, null)))
        .thenReturn(new FindSkusResponse(List.of(), "empty-page"));
    when(inventory.findSkus(ctx, new FindSkusRequest(null, null, true, 100, "empty-page")))
        .thenReturn(new FindSkusResponse(List.of(sku("sku-1"), sku("sku-2")), "next"));
    when(inventory.findSkus(ctx, new FindSkusRequest(null, null, true, 100, "next")))
        .thenReturn(new FindSkusResponse(List.of(sku("sku-3")), null));
    publishRepository.createPublish(USER, "publish-1", Instant.ofEpochSecond(10));

    var response =
        new GetPublishService(publishRepository, inventory)
            .getPublish(ctx, new GetPublishRequest());

    assertThat(response).isEqualTo(new GetPublishResponse(PublishStatus.QUEUED, 0, 10, null, 3));
    verify(inventory).findSkus(ctx, new FindSkusRequest(null, null, true, 100, null));
    verify(inventory).findSkus(ctx, new FindSkusRequest(null, null, true, 100, "empty-page"));
    verify(inventory).findSkus(ctx, new FindSkusRequest(null, null, true, 100, "next"));
  }

  @Test
  void getServiceShouldNotReturnPartialCountWhenLaterPageFails() throws Exception {
    var ctx = new RequestContext(USER);
    var inventory = mock(InventoryService.class);
    when(inventory.findSkus(ctx, new FindSkusRequest(null, null, true, 100, null)))
        .thenReturn(new FindSkusResponse(List.of(sku("sku-1")), "next"));
    var failure = new BadRequestException("inventory failed");
    when(inventory.findSkus(ctx, new FindSkusRequest(null, null, true, 100, "next")))
        .thenThrow(failure);
    publishRepository.createPublish(USER, "publish-1", Instant.ofEpochSecond(10));

    assertThatThrownBy(
            () ->
                new GetPublishService(publishRepository, inventory)
                    .getPublish(ctx, new GetPublishRequest()))
        .isSameAs(failure);
  }

  @Test
  void getServiceShouldScopeRunLookupToTheRequestUser() throws Exception {
    publishRepository.createPublish(USER, "alice-run", Instant.ofEpochSecond(10));
    var inventory = mock(InventoryService.class);

    assertThatThrownBy(
            () ->
                new GetPublishService(publishRepository, inventory)
                    .getPublish(new RequestContext("bob"), new GetPublishRequest()))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");

    verify(inventory, never()).findSkus(any(), any());
  }

  @Test
  void getServiceShouldFailVisiblyForUnknownStatus() throws Exception {
    var inventory = mock(InventoryService.class);
    publishRepository.createPublish(USER, "publish-1", Instant.ofEpochSecond(10));
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(TABLE_NAME)
            .key(key(USER))
            .updateExpression("SET #status = :status")
            .expressionAttributeNames(Map.of("#status", PublishItem.STATUS))
            .expressionAttributeValues(Map.of(":status", AttributeValue.fromS("failed")))
            .build());

    assertThatThrownBy(
            () ->
                new GetPublishService(publishRepository, inventory)
                    .getPublish(new RequestContext(USER), new GetPublishRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Publish run has an unknown status");

    verify(inventory, never()).findSkus(any(), any());
  }

  @Test
  void createProcessAndReadShouldWorkThroughClientOperations() throws Exception {
    var ctx = new RequestContext(USER);
    var queue = new FakeQueueClient<PublishMessage>();
    var createService = new CreatePublishService(publishRepository, queue, clock, ulidGenerator);
    var games = new FakeGamesService();
    var inventory = new FakeInventoryService(games);
    var sku = sku("mtg#scryfall#card-1#normal#NM");
    inventory.seedSku(USER, sku, true);
    inventory.seedUnits(
        USER,
        sku.skuId(),
        List.of(new Unit(0, "A0-0", UnitStatus.IN_STOCK, List.of(), 1L, null, null)));
    var listings = new FakeListingService(inventory);
    var orderService = mock(OrderService.class);

    createService.createPublish(ctx, new CreatePublishRequest());
    var message = queue.getMessages().getFirst();
    new PublishMessageHandler(publishRepository, queue, clock, orderService, inventory, listings)
        .handleMessage(ctx, message);
    var response =
        new GetPublishService(publishRepository, inventory)
            .getPublish(ctx, new GetPublishRequest());

    assertThat(response)
        .isEqualTo(
            new GetPublishResponse(
                PublishStatus.SUCCEEDED,
                1,
                clock.now().getEpochSecond(),
                clock.now().getEpochSecond(),
                0));
    verify(orderService).updateOrders(ctx, new UpdateOrdersRequest());
  }

  @Test
  void messageHandlerShouldRunOrdersBeforeListingsAndCompleteTheRun() throws Exception {
    var ctx = new RequestContext(USER);
    var message = new PublishMessage(USER, "publish-1");
    var sku = sku("mtg#scryfall#card-1#normal#NM");
    var orderService = mock(OrderService.class);
    var inventoryService = mock(InventoryService.class);
    var listingService = mock(ListingService.class);
    var queue = new FakeQueueClient<PublishMessage>();
    when(inventoryService.findSkus(ctx, new FindSkusRequest(null, null, true, 100, null)))
        .thenReturn(new FindSkusResponse(List.of(sku), null));
    publishRepository.createPublish(USER, message.publishId(), clock.now());

    var handler =
        new PublishMessageHandler(
            publishRepository, queue, clock, orderService, inventoryService, listingService);
    handler.handleMessage(ctx, message);

    var order = inOrder(orderService, inventoryService, listingService);
    order.verify(orderService).updateOrders(ctx, new UpdateOrdersRequest());
    order.verify(inventoryService).findSkus(ctx, new FindSkusRequest(null, null, true, 100, null));
    order.verify(listingService).updateListing(ctx, new UpdateListingRequest(sku.skuId()));
    order.verifyNoMoreInteractions();
    assertThat(publishRepository.get(USER).getStatus()).isEqualTo("succeeded");
    assertThat(publishRepository.get(USER).getPublishedSkuCount()).isEqualTo(1);
    assertThat(publishRepository.get(USER).getFinishedAt()).isEqualTo(clock.now());
    assertThat(queue.getSends()).isEmpty();
  }

  @Test
  void messageHandlerShouldUseInventoryAndListingsFakesForNormalSynchronization() throws Exception {
    var ctx = new RequestContext(USER);
    var sku = sku("mtg#scryfall#card-1#normal#NM");
    var games = new FakeGamesService();
    var inventory = new FakeInventoryService(games);
    inventory.seedSku(USER, sku, true);
    inventory.seedUnits(
        USER,
        sku.skuId(),
        List.of(new Unit(0, "A0-0", UnitStatus.IN_STOCK, List.of(), 1L, null, null)));
    var listings = new FakeListingService(inventory);
    var orderService = mock(OrderService.class);
    var queue = new FakeQueueClient<PublishMessage>();
    var message = new PublishMessage(USER, "publish-1");
    publishRepository.createPublish(USER, message.publishId(), clock.now());
    var handler =
        new PublishMessageHandler(
            publishRepository, queue, clock, orderService, inventory, listings);

    handler.handleMessage(ctx, message);

    assertThat(listings.findListings(ctx, new FindListingsRequest(List.of(1_000_000))).listings())
        .containsExactly(new Listing(1_000_000, sku.skuId()));
    assertThat(inventory.findSkus(ctx, new FindSkusRequest(null, null, true, 100, null)).skus())
        .isEmpty();
    assertThat(publishRepository.get(USER).getPublishedSkuCount()).isEqualTo(1);
    verify(orderService).updateOrders(ctx, new UpdateOrdersRequest());
  }

  @Test
  void messageHandlerShouldUseCheckpointCountToSkipOrdersOnLaterDeliveries() throws Exception {
    var ctx = new RequestContext(USER);
    var message = new PublishMessage(USER, "publish-1");
    var inventory = mock(InventoryService.class);
    var listings = mock(ListingService.class);
    var orderService = mock(OrderService.class);
    var queue = new FakeQueueClient<PublishMessage>();
    when(inventory.findSkus(any(), any()))
        .thenReturn(new FindSkusResponse(List.of(sku("sku-1")), "provider-token"))
        .thenReturn(new FindSkusResponse(List.of(sku("sku-2")), null));
    publishRepository.createPublish(USER, message.publishId(), clock.now());
    var handler =
        new PublishMessageHandler(
            publishRepository, queue, clock, orderService, inventory, listings);

    handler.handleMessage(ctx, message);
    assertThat(publishRepository.get(USER).getPublishedSkuCount()).isEqualTo(1);
    assertThat(queue.getSends())
        .containsExactly(new FakeQueueClient.SentMessage<>(message, USER, "publish-1#1"));
    handler.handleMessage(ctx, message);

    verify(orderService, times(1)).updateOrders(ctx, new UpdateOrdersRequest());
    verify(inventory, times(2)).findSkus(ctx, new FindSkusRequest(null, null, true, 100, null));
    verify(listings).updateListing(ctx, new UpdateListingRequest("sku-1"));
    verify(listings).updateListing(ctx, new UpdateListingRequest("sku-2"));
    assertThat(publishRepository.get(USER).getStatus()).isEqualTo("succeeded");
    assertThat(queue.getSends()).hasSize(1);
  }

  @Test
  void firstBatchFailureShouldKeepCountAndRetryOrders() throws Exception {
    var ctx = new RequestContext(USER);
    var message = new PublishMessage(USER, "publish-1");
    var inventory = mock(InventoryService.class);
    var listings = mock(ListingService.class);
    var orderService = mock(OrderService.class);
    var queue = new FakeQueueClient<PublishMessage>();
    var failedSku = sku("sku-2");
    when(inventory.findSkus(any(), any()))
        .thenReturn(new FindSkusResponse(List.of(sku("sku-1"), failedSku), null));
    when(listings.updateListing(any(), any()))
        .thenReturn(new UpdateListingResponse())
        .thenThrow(new BadRequestException("listing failed"))
        .thenReturn(new UpdateListingResponse())
        .thenReturn(new UpdateListingResponse());
    publishRepository.createPublish(USER, message.publishId(), clock.now());
    var handler =
        new PublishMessageHandler(
            publishRepository, queue, clock, orderService, inventory, listings);

    assertThatThrownBy(() -> handler.handleMessage(ctx, message))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("listing failed");
    assertThat(publishRepository.get(USER).getStatus()).isEqualTo("running");
    assertThat(publishRepository.get(USER).getPublishedSkuCount()).isZero();
    assertThat(queue.getSends()).isEmpty();

    handler.handleMessage(ctx, message);

    verify(orderService, times(2)).updateOrders(ctx, new UpdateOrdersRequest());
    verify(listings, times(4)).updateListing(any(), any());
    assertThat(publishRepository.get(USER).getStatus()).isEqualTo("succeeded");
    assertThat(publishRepository.get(USER).getPublishedSkuCount()).isEqualTo(2);
  }

  @Test
  void orderFailureShouldPreventInventoryAndListingsWork() throws Exception {
    var ctx = new RequestContext(USER);
    var message = new PublishMessage(USER, "publish-1");
    var inventory = mock(InventoryService.class);
    var listings = mock(ListingService.class);
    var orderService = mock(OrderService.class);
    var queue = new FakeQueueClient<PublishMessage>();
    var failure = new BadRequestException("orders failed");
    doThrow(failure).when(orderService).updateOrders(ctx, new UpdateOrdersRequest());
    publishRepository.createPublish(USER, message.publishId(), clock.now());
    var handler =
        new PublishMessageHandler(
            publishRepository, queue, clock, orderService, inventory, listings);

    assertThatThrownBy(() -> handler.handleMessage(ctx, message)).isSameAs(failure);

    verify(inventory, never()).findSkus(any(), any());
    verify(listings, never()).updateListing(any(), any());
    assertThat(publishRepository.get(USER).getStatus()).isEqualTo("running");
    assertThat(publishRepository.get(USER).getPublishedSkuCount()).isZero();
  }

  @Test
  void laterBatchFailureShouldSkipOrdersAndLeaveCheckpointUnchanged() throws Exception {
    var ctx = new RequestContext(USER);
    var message = new PublishMessage(USER, "publish-1");
    var inventory = mock(InventoryService.class);
    var listings = mock(ListingService.class);
    var orderService = mock(OrderService.class);
    var queue = new FakeQueueClient<PublishMessage>();
    publishRepository.createPublish(USER, message.publishId(), clock.now());
    publishRepository.startPublish(USER, message.publishId(), clock.now());
    publishRepository.checkpointPublish(USER, message.publishId(), 0, 1, clock.now());
    when(inventory.findSkus(any(), any()))
        .thenReturn(new FindSkusResponse(List.of(sku("sku-2")), null));
    var failure = new BadRequestException("listing failed");
    doThrow(failure).when(listings).updateListing(any(), any());
    var handler =
        new PublishMessageHandler(
            publishRepository, queue, clock, orderService, inventory, listings);

    assertThatThrownBy(() -> handler.handleMessage(ctx, message)).isSameAs(failure);

    verify(orderService, never()).updateOrders(any(), any());
    assertThat(publishRepository.get(USER).getStatus()).isEqualTo("running");
    assertThat(publishRepository.get(USER).getPublishedSkuCount()).isEqualTo(1);
  }

  @Test
  void dispatchFailureShouldLeaveCheckpointForRedelivery() throws Exception {
    var ctx = new RequestContext(USER);
    var message = new PublishMessage(USER, "publish-1");
    var inventory = mock(InventoryService.class);
    var listings = mock(ListingService.class);
    var orderService = mock(OrderService.class);
    var queue = mockQueue();
    when(inventory.findSkus(any(), any()))
        .thenReturn(new FindSkusResponse(List.of(sku("sku-1")), "provider-token"));
    var failure = new IllegalStateException("queue unavailable");
    doThrow(failure).when(queue).send(message, USER, "publish-1#1");
    publishRepository.createPublish(USER, message.publishId(), clock.now());
    var handler =
        new PublishMessageHandler(
            publishRepository, queue, clock, orderService, inventory, listings);

    assertThatThrownBy(() -> handler.handleMessage(ctx, message)).isSameAs(failure);

    assertThat(publishRepository.get(USER).getStatus()).isEqualTo("running");
    assertThat(publishRepository.get(USER).getPublishedSkuCount()).isEqualTo(1);
  }

  @Test
  void checkpointFailureShouldPreventContinuationDispatch() throws Exception {
    var ctx = new RequestContext(USER);
    var message = new PublishMessage(USER, "publish-1");
    var repository = mock(PublishRepository.class);
    var run = new PublishItem();
    run.setUser(USER);
    run.setPublishId(message.publishId());
    run.setStatus("running");
    run.setPublishedSkuCount(0);
    run.setStartedAt(clock.now());
    run.setUpdatedAt(clock.now());
    when(repository.get(USER)).thenReturn(run);
    var inventory = mock(InventoryService.class);
    var listings = mock(ListingService.class);
    var orderService = mock(OrderService.class);
    var queue = new FakeQueueClient<PublishMessage>();
    when(inventory.findSkus(any(), any()))
        .thenReturn(new FindSkusResponse(List.of(sku("sku-1")), "provider-token"));
    var failure = new IllegalStateException("checkpoint failed");
    doThrow(failure)
        .when(repository)
        .checkpointPublish(eq(USER), eq(message.publishId()), eq(0), eq(1), any());
    var handler =
        new PublishMessageHandler(repository, queue, clock, orderService, inventory, listings);

    assertThatThrownBy(() -> handler.handleMessage(ctx, message)).isSameAs(failure);

    assertThat(queue.getSends()).isEmpty();
  }

  @Test
  void missingRunMetadataShouldFailBeforeCallingDependencies() throws Exception {
    var ctx = new RequestContext(USER);
    var inventory = mock(InventoryService.class);
    var listings = mock(ListingService.class);
    var orderService = mock(OrderService.class);
    var handler =
        new PublishMessageHandler(
            publishRepository,
            new FakeQueueClient<PublishMessage>(),
            clock,
            orderService,
            inventory,
            listings);

    assertThatThrownBy(() -> handler.handleMessage(ctx, new PublishMessage(USER, "publish-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Publish run metadata is missing or incomplete");

    verify(orderService, never()).updateOrders(any(), any());
    verify(inventory, never()).findSkus(any(), any());
    verify(listings, never()).updateListing(any(), any());
  }

  @Test
  void unknownRunStatusShouldFailVisibly() throws Exception {
    var ctx = new RequestContext(USER);
    var inventory = mock(InventoryService.class);
    var listings = mock(ListingService.class);
    var orderService = mock(OrderService.class);
    publishRepository.createPublish(USER, "publish-1", clock.now());
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(TABLE_NAME)
            .key(key(USER))
            .updateExpression("SET #status = :status")
            .expressionAttributeNames(Map.of("#status", PublishItem.STATUS))
            .expressionAttributeValues(Map.of(":status", AttributeValue.fromS("failed")))
            .build());
    var handler =
        new PublishMessageHandler(
            publishRepository,
            new FakeQueueClient<PublishMessage>(),
            clock,
            orderService,
            inventory,
            listings);

    assertThatThrownBy(() -> handler.handleMessage(ctx, new PublishMessage(USER, "publish-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Publish run has an unknown status");

    verify(orderService, never()).updateOrders(any(), any());
    verify(inventory, never()).findSkus(any(), any());
    verify(listings, never()).updateListing(any(), any());
  }

  @Test
  void completedAndSupersededMessagesShouldDoNoWork() throws Exception {
    var ctx = new RequestContext(USER);
    var orderService = mock(OrderService.class);
    var inventory = mock(InventoryService.class);
    var listings = mock(ListingService.class);
    var queue = new FakeQueueClient<PublishMessage>();
    var handler =
        new PublishMessageHandler(
            publishRepository, queue, clock, orderService, inventory, listings);
    publishRepository.createPublish(USER, "old", clock.now());
    publishRepository.startPublish(USER, "old", clock.now());
    publishRepository.completePublish(USER, "old", 0, 0, clock.now());

    handler.handleMessage(ctx, new PublishMessage(USER, "old"));
    publishRepository.createPublish(USER, "new", clock.now());

    handler.handleMessage(ctx, new PublishMessage(USER, "old"));

    verify(orderService, never()).updateOrders(any(), any());
    verify(inventory, never()).findSkus(any(), any());
    verify(listings, never()).updateListing(any(), any());
    assertThat(queue.getSends()).isEmpty();
  }

  @Test
  void emptyRunShouldCompleteAtZeroAndMessageUsesExpectedJson() throws Exception {
    var ctx = new RequestContext(USER);
    var message = new PublishMessage(USER, "publish-1");
    var inventory = mock(InventoryService.class);
    var listings = mock(ListingService.class);
    var orderService = mock(OrderService.class);
    when(inventory.findSkus(any(), any())).thenReturn(new FindSkusResponse(List.of(), null));
    publishRepository.createPublish(USER, message.publishId(), clock.now());
    var handler =
        new PublishMessageHandler(
            publishRepository,
            new FakeQueueClient<PublishMessage>(),
            clock,
            orderService,
            inventory,
            listings);

    handler.handleMessage(ctx, message);

    assertThat(publishRepository.get(USER).getStatus()).isEqualTo("succeeded");
    assertThat(publishRepository.get(USER).getPublishedSkuCount()).isZero();
    var mapper = new ObjectMapper();
    assertThat(mapper.writeValueAsString(message))
        .isEqualTo("{\"user\":\"alice\",\"publish_id\":\"publish-1\"}");
  }

  private Sku sku(String skuId) {
    return new Sku(
        skuId,
        "mtg",
        skuId,
        "card",
        "set",
        "Set",
        "1",
        "normal",
        Condition.NM,
        new BigDecimal("1.00"),
        "fetch-card",
        1,
        0);
  }

  @SuppressWarnings("unchecked")
  private QueueClient<PublishMessage> mockQueue() {
    return mock(QueueClient.class);
  }

  private Map<String, AttributeValue> getRaw(String user) {
    return dynamoDbClient
        .getItem(
            GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key(user))
                .consistentRead(true)
                .build())
        .item();
  }

  private Map<String, AttributeValue> key(String user) {
    return Map.of(
        PublishItem.PK, AttributeValue.fromS(PublishItem.formatPk(user)),
        PublishItem.SK, AttributeValue.fromS(PublishItem.formatSk()));
  }
}
