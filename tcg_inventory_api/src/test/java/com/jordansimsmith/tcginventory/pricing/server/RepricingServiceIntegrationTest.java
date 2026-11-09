package com.jordansimsmith.tcginventory.pricing.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.jordansimsmith.tcginventory.inventory.client.FindSkusResponse;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsResponse;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuResponse;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.pricing.client.CreateRepricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.FakePricingService;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceResponse;
import com.jordansimsmith.tcginventory.pricing.client.GetRepricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetRepricingResponse;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import com.jordansimsmith.tcginventory.pricing.client.RepricingStatus;
import com.jordansimsmith.time.FakeClock;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@Testcontainers
class RepricingServiceIntegrationTest {
  private static final String TABLE_NAME = "repricing_test";
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<RepricingItem> repricingTable;
  private RepricingRepository repricingRepository;

  @BeforeAll
  static void setUpBeforeClass() {
    dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    repricingTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(RepricingItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, repricingTable);
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    repricingRepository = new RepricingRepository(repricingTable, dynamoDbClient, TABLE_NAME);
  }

  @Test
  void getShouldReturnNullWhenRunDoesNotExist() {
    // act
    var item = repricingRepository.get("alice");

    // assert
    assertThat(item).isNull();
    assertThat(repricingRepository.get("bob")).isNull();
  }

  @Test
  void lifecycleShouldStoreNumericTimesAndAdvanceCheckpointAtomically() {
    // arrange
    var startedAt = Instant.ofEpochSecond(1_700_000_001L);
    var runningAt = Instant.ofEpochSecond(1_700_000_002L);
    var checkpointAt = Instant.ofEpochSecond(1_700_000_003L);
    var finishedAt = Instant.ofEpochSecond(1_700_000_004L);

    // act
    repricingRepository.createRepricing("alice", "run-1", startedAt);
    var queued = repricingRepository.get("alice");
    repricingRepository.startRepricing("alice", "run-1", runningAt);
    repricingRepository.checkpointRepricing("alice", "run-1", 0, 7, "opaque-page-2", checkpointAt);
    repricingRepository.completeRepricing("alice", "run-1", 7, 7, finishedAt);

    // assert
    assertThat(queued.getProcessedSkuCount()).isZero();
    assertThat(queued.getStartedAt()).isEqualTo(startedAt);
    var completed = repricingRepository.get("alice");
    assertThat(completed.getUser()).isEqualTo("alice");
    assertThat(completed.getRepricingId()).isEqualTo("run-1");
    assertThat(completed.getStatus()).isEqualTo("succeeded");
    assertThat(completed.getProcessedSkuCount()).isEqualTo(7);
    assertThat(completed.getContinuation()).isNull();
    assertThat(completed.getStartedAt()).isEqualTo(startedAt);
    assertThat(completed.getUpdatedAt()).isEqualTo(finishedAt);
    assertThat(completed.getFinishedAt()).isEqualTo(finishedAt);
    assertThat(getRaw("alice"))
        .containsEntry(RepricingItem.PK, AttributeValue.fromS("USER#alice"))
        .containsEntry(RepricingItem.SK, AttributeValue.fromS("REPRICING"))
        .containsEntry(RepricingItem.PROCESSED_SKU_COUNT, AttributeValue.fromN("7"))
        .containsEntry(RepricingItem.STARTED_AT, AttributeValue.fromN("1700000001"))
        .containsEntry(RepricingItem.UPDATED_AT, AttributeValue.fromN("1700000004"))
        .containsEntry(RepricingItem.FINISHED_AT, AttributeValue.fromN("1700000004"))
        .doesNotContainKey(RepricingItem.CONTINUATION);
  }

  @Test
  void createShouldRejectActiveRunsAndReplaceSucceededRunWithoutTouchingOtherAttributes() {
    // arrange
    repricingRepository.createRepricing("alice", "run-1", Instant.ofEpochSecond(1));
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(TABLE_NAME)
            .key(key("alice"))
            .updateExpression("SET #other = :other")
            .expressionAttributeNames(Map.of("#other", "other_attribute"))
            .expressionAttributeValues(Map.of(":other", AttributeValue.fromS("preserved")))
            .build());

    // act / assert
    assertThatThrownBy(
            () -> repricingRepository.createRepricing("alice", "run-2", Instant.ofEpochSecond(2)))
        .isInstanceOf(ConditionalCheckFailedException.class);
    repricingRepository.startRepricing("alice", "run-1", Instant.ofEpochSecond(3));
    assertThatThrownBy(
            () -> repricingRepository.createRepricing("alice", "run-2", Instant.ofEpochSecond(4)))
        .isInstanceOf(ConditionalCheckFailedException.class);

    repricingRepository.checkpointRepricing(
        "alice", "run-1", 0, 5, "old-page", Instant.ofEpochSecond(5));
    repricingRepository.completeRepricing("alice", "run-1", 5, 5, Instant.ofEpochSecond(6));
    repricingRepository.createRepricing("alice", "run-2", Instant.ofEpochSecond(7));

    var item = repricingRepository.get("alice");
    assertThat(item.getRepricingId()).isEqualTo("run-2");
    assertThat(item.getStatus()).isEqualTo("queued");
    assertThat(item.getProcessedSkuCount()).isZero();
    assertThat(item.getContinuation()).isNull();
    assertThat(item.getFinishedAt()).isNull();
    assertThat(getRaw("alice")).containsEntry("other_attribute", AttributeValue.fromS("preserved"));
  }

  @Test
  void emptyTerminalRunShouldCompleteWithoutAdvancingCount() {
    // arrange
    repricingRepository.createRepricing("alice", "run-1", Instant.EPOCH);
    repricingRepository.startRepricing("alice", "run-1", Instant.ofEpochSecond(1));

    // act
    repricingRepository.completeRepricing("alice", "run-1", 0, 0, Instant.ofEpochSecond(2));

    // assert
    var item = repricingRepository.get("alice");
    assertThat(item.getProcessedSkuCount()).isZero();
    assertThat(item.getStatus()).isEqualTo("succeeded");
    assertThat(item.getFinishedAt()).isEqualTo(Instant.ofEpochSecond(2));
    assertThat(item.getContinuation()).isNull();
  }

  @Test
  void mutationsShouldRejectStaleIdentityStatusAndCountWithoutChangingState() {
    // arrange
    repricingRepository.createRepricing("alice", "run-1", Instant.EPOCH);
    repricingRepository.startRepricing("alice", "run-1", Instant.ofEpochSecond(1));

    // act / assert
    assertThatThrownBy(
            () ->
                repricingRepository.checkpointRepricing(
                    "alice", "other-run", 0, 1, "next", Instant.ofEpochSecond(2)))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThatThrownBy(
            () ->
                repricingRepository.checkpointRepricing(
                    "alice", "run-1", 1, 2, "next", Instant.ofEpochSecond(2)))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThatThrownBy(
            () -> repricingRepository.startRepricing("alice", "run-1", Instant.ofEpochSecond(2)))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThat(repricingRepository.get("alice").getProcessedSkuCount()).isZero();

    repricingRepository.checkpointRepricing(
        "alice", "run-1", 0, 3, "next-page", Instant.ofEpochSecond(3));
    assertThatThrownBy(
            () ->
                repricingRepository.checkpointRepricing(
                    "alice", "run-1", 0, 4, "stale-page", Instant.ofEpochSecond(4)))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThatThrownBy(
            () ->
                repricingRepository.completeRepricing(
                    "alice", "run-1", 0, 3, Instant.ofEpochSecond(5)))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThat(repricingRepository.get("alice").getProcessedSkuCount()).isEqualTo(3);
  }

  @Test
  void readsAndWritesShouldRemainUserScopedAndPreserveFloorRecord() {
    // arrange
    repricingRepository.createRepricing("alice", "alice-run", Instant.ofEpochSecond(1));
    repricingRepository.createRepricing("bob", "bob-run", Instant.ofEpochSecond(2));
    var pricingTable =
        DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build()
            .table(TABLE_NAME, TableSchema.fromBean(PricingItem.class));
    pricingTable.putItem(PricingItem.create("alice", "0.50"));

    // act
    var alice = repricingRepository.get("alice");
    var bob = repricingRepository.get("bob");

    // assert
    assertThat(alice.getRepricingId()).isEqualTo("alice-run");
    assertThat(bob.getRepricingId()).isEqualTo("bob-run");
    assertThat(
            pricingTable
                .getItem(r -> r.key(k -> k.partitionValue("USER#alice").sortValue("PRICING")))
                .getPriceFloor())
        .isEqualTo("0.50");
    assertThat(getRaw("alice")).containsEntry(RepricingItem.SK, AttributeValue.fromS("REPRICING"));
  }

  @Test
  void checkpointShouldRequireTheCheckedCountToAdvance() {
    // arrange
    repricingRepository.createRepricing("alice", "run-1", Instant.EPOCH);
    repricingRepository.startRepricing("alice", "run-1", Instant.ofEpochSecond(1));

    // act / assert
    assertThatThrownBy(
            () ->
                repricingRepository.checkpointRepricing(
                    "alice", "run-1", 0, 0, "next", Instant.ofEpochSecond(2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("checkpoint must advance processed_sku_count");
    assertThat(repricingRepository.get("alice").getProcessedSkuCount()).isZero();
  }

  @Test
  void repositoryShouldPropagateStorageFailures() {
    // arrange
    var failure = new IllegalStateException("storage failed");
    @SuppressWarnings("unchecked")
    var table = (DynamoDbTable<RepricingItem>) mock(DynamoDbTable.class);
    when(table.getItem(any(GetItemEnhancedRequest.class))).thenThrow(failure);
    var readFailingRepository = new RepricingRepository(table, dynamoDbClient, TABLE_NAME);
    var client = mock(DynamoDbClient.class);
    when(client.updateItem(any(UpdateItemRequest.class))).thenThrow(failure);
    var writeFailingRepository = new RepricingRepository(repricingTable, client, TABLE_NAME);

    // act / assert
    assertThatThrownBy(() -> readFailingRepository.get("alice")).isSameAs(failure);
    assertThatThrownBy(
            () -> writeFailingRepository.createRepricing("alice", "run-1", Instant.EPOCH))
        .isSameAs(failure);
  }

  @Test
  void repricingOperationsShouldCreateOnceAndExposeEveryRunStatus() throws Exception {
    // arrange
    var clock = new FakeClock();
    clock.setTime(Instant.ofEpochSecond(1_700_000_001L));
    var ids = new FakeUlidGenerator();
    var queue = new FakeQueueClient<RepricingMessage>();
    var create = new CreateRepricingService(repricingRepository, queue, clock, ids);
    var get = new GetRepricingService(repricingRepository);
    var ctx = new RequestContext("alice");

    // act
    create.createRepricing(ctx, new CreateRepricingRequest());
    create.createRepricing(ctx, new CreateRepricingRequest());

    // assert queued
    var message = new RepricingMessage("alice", "FAKE_ULID_0000000001");
    assertThat(queue.getSends())
        .containsExactly(
            new FakeQueueClient.SentMessage<>(
                message, message.repricingId(), message.repricingId()));
    assertThat(get.getRepricing(ctx, new GetRepricingRequest()))
        .isEqualTo(new GetRepricingResponse(RepricingStatus.QUEUED, 0, 1_700_000_001L, null));

    // act / assert running
    repricingRepository.startRepricing("alice", message.repricingId(), clock.now());
    repricingRepository.checkpointRepricing(
        "alice", message.repricingId(), 0, 4, "next", clock.now());
    assertThat(get.getRepricing(ctx, new GetRepricingRequest()).status())
        .isEqualTo(RepricingStatus.RUNNING);
    assertThat(get.getRepricing(ctx, new GetRepricingRequest()).processedSkuCount()).isEqualTo(4);
    create.createRepricing(ctx, new CreateRepricingRequest());
    assertThat(queue.getSends()).hasSize(1);

    // act / assert succeeded
    repricingRepository.completeRepricing("alice", message.repricingId(), 4, 4, clock.now());
    var succeeded = get.getRepricing(ctx, new GetRepricingRequest());
    assertThat(succeeded.status()).isEqualTo(RepricingStatus.SUCCEEDED);
    assertThat(succeeded.finishedAt()).isEqualTo(1_700_000_001L);
    create.createRepricing(ctx, new CreateRepricingRequest());

    assertThat(repricingRepository.get("alice").getRepricingId()).isEqualTo("FAKE_ULID_0000000002");
    assertThat(queue.getSends()).hasSize(2);
    assertThat(repricingRepository.get("bob")).isNull();
    assertThatThrownBy(() -> get.getRepricing(new RequestContext("bob"), new GetRepricingRequest()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void getRepricingShouldReturnNotFoundForUsersWithoutARun() {
    // arrange
    var service = new GetRepricingService(repricingRepository);

    // act / assert
    assertThatThrownBy(
            () -> service.getRepricing(new RequestContext("alice"), new GetRepricingRequest()))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");
  }

  @Test
  void repricingOperationsShouldLeaveRunValidationToMessageHandler() throws Exception {
    // arrange
    var item = new RepricingItem();
    item.setPk(RepricingItem.formatPk("alice"));
    item.setSk(RepricingItem.formatSk());
    item.setUser("alice");
    item.setRepricingId("run-1");
    item.setStatus("queued");
    item.setProcessedSkuCount(1);
    item.setStartedAt(Instant.EPOCH);
    item.setUpdatedAt(Instant.EPOCH);
    repricingTable.putItem(item);
    var get = new GetRepricingService(repricingRepository);
    var create =
        new CreateRepricingService(
            repricingRepository, new FakeQueueClient<>(), new FakeClock(), new FakeUlidGenerator());
    var inventory = mock(InventoryService.class);
    var pricing = mock(PricingService.class);
    var handler = newHandler(new FakeQueueClient<>(), new FakeClock(), inventory, pricing);

    // act / assert
    create.createRepricing(new RequestContext("alice"), new CreateRepricingRequest());
    assertThat(get.getRepricing(new RequestContext("alice"), new GetRepricingRequest()))
        .isEqualTo(new GetRepricingResponse(RepricingStatus.QUEUED, 1, 0, null));
    assertThatThrownBy(
            () ->
                handler.handleMessage(
                    new RequestContext("alice"), new RepricingMessage("alice", "run-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Queued repricing run has inconsistent progress metadata");
    verifyNoInteractions(inventory, pricing);

    item.setStatus("unknown");
    item.setProcessedSkuCount(0);
    repricingTable.putItem(item);
    assertThatThrownBy(
            () -> get.getRepricing(new RequestContext("alice"), new GetRepricingRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Repricing run has an unknown status");
  }

  @Test
  void createRepricingShouldLeaveQueuedRunWhenDispatchFailsAndNotResend() throws Exception {
    // arrange
    @SuppressWarnings("unchecked")
    var failingQueue = (QueueClient<RepricingMessage>) mock(QueueClient.class);
    doThrow(new IllegalStateException("queue failed")).when(failingQueue).send(any(), any(), any());
    var create =
        new CreateRepricingService(
            repricingRepository, failingQueue, new FakeClock(), new FakeUlidGenerator());
    var ctx = new RequestContext("alice");

    // act / assert
    assertThatThrownBy(() -> create.createRepricing(ctx, new CreateRepricingRequest()))
        .hasMessage("queue failed");
    assertThat(repricingRepository.get("alice").getStatus()).isEqualTo("queued");

    var retryQueue = new FakeQueueClient<RepricingMessage>();
    new CreateRepricingService(
            repricingRepository, retryQueue, new FakeClock(), new FakeUlidGenerator())
        .createRepricing(ctx, new CreateRepricingRequest());
    assertThat(retryQueue.getMessages()).isEmpty();
  }

  @Test
  void createRepricingShouldNotDispatchWhenRunReadFails() {
    // arrange
    @SuppressWarnings("unchecked")
    var table = (DynamoDbTable<RepricingItem>) mock(DynamoDbTable.class);
    var failure = new IllegalStateException("storage failed");
    when(table.getItem(any(GetItemEnhancedRequest.class))).thenThrow(failure);
    var repository = new RepricingRepository(table, dynamoDbClient, TABLE_NAME);
    @SuppressWarnings("unchecked")
    var queue = (QueueClient<RepricingMessage>) mock(QueueClient.class);
    var create =
        new CreateRepricingService(repository, queue, new FakeClock(), new FakeUlidGenerator());

    // act / assert
    assertThatThrownBy(
            () -> create.createRepricing(new RequestContext("alice"), new CreateRepricingRequest()))
        .isSameAs(failure);
    verifyNoInteractions(queue);
  }

  @Test
  void repricingResponseShouldSerializeOnlyPublicFields() throws Exception {
    // arrange
    repricingRepository.createRepricing("alice", "run-1", Instant.ofEpochSecond(1_700_000_001L));
    var json =
        new ObjectMapper()
            .writeValueAsString(
                new GetRepricingService(repricingRepository)
                    .getRepricing(new RequestContext("alice"), new GetRepricingRequest()));

    // assert
    assertThat(json)
        .isEqualTo(
            "{\"status\":\"queued\",\"processed_sku_count\":0,"
                + "\"started_at\":1700000001,\"finished_at\":null}");
  }

  @Test
  void repricingOperationsShouldPreserveFloorConfiguration() throws Exception {
    // arrange
    var pricingTable =
        DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build()
            .table(TABLE_NAME, TableSchema.fromBean(PricingItem.class));
    pricingTable.putItem(PricingItem.create("alice", "0.50"));

    // act
    new CreateRepricingService(
            repricingRepository,
            new FakeQueueClient<RepricingMessage>(),
            new FakeClock(),
            new FakeUlidGenerator())
        .createRepricing(new RequestContext("alice"), new CreateRepricingRequest());

    // assert
    assertThat(
            pricingTable
                .getItem(Key.builder().partitionValue("USER#alice").sortValue("PRICING").build())
                .getPriceFloor())
        .isEqualTo("0.50");
  }

  @Test
  void createProcessAndGetShouldExposeTheCompletedRun() throws Exception {
    // arrange
    var clock = new FakeClock();
    clock.setTime(Instant.ofEpochSecond(1_700_000_001L));
    var queue = new FakeQueueClient<RepricingMessage>();
    var ctx = new RequestContext("alice");
    var create =
        new CreateRepricingService(repricingRepository, queue, clock, new FakeUlidGenerator());
    create.createRepricing(ctx, new CreateRepricingRequest());
    var inventory = new FakeInventoryService(new FakeGamesService());
    var handler = newHandler(queue, clock, inventory, new FakePricingService());

    // act
    handler.handleMessage(ctx, queue.getMessages().getFirst());
    var response =
        new GetRepricingService(repricingRepository).getRepricing(ctx, new GetRepricingRequest());

    // assert
    assertThat(response.status()).isEqualTo(RepricingStatus.SUCCEEDED);
    assertThat(response.processedSkuCount()).isZero();
    assertThat(response.startedAt()).isEqualTo(1_700_000_001L);
    assertThat(response.finishedAt()).isEqualTo(1_700_000_001L);
  }

  @Test
  void messageHandlerShouldPriceStockAndCompleteRun() throws Exception {
    // arrange
    var startedAt = Instant.ofEpochSecond(100);
    var clock = new FakeClock();
    clock.setTime(startedAt);
    repricingRepository.createRepricing("alice", "run-1", startedAt);
    var games = new FakeGamesService();
    var inventory = new FakeInventoryService(games);
    var pricing = new FakePricingService();
    var sku = sku("mtg#scryfall#card-1#normal#NM", "card-1");
    inventory.seedSku("alice", sku, false);
    var units = new ArrayList<Unit>();
    for (var sequence = 0; sequence < 100; sequence++) {
      units.add(new Unit(sequence, "A0-" + sequence, UnitStatus.REMOVED, List.of(), 1, null, null));
    }
    units.add(new Unit(100, "A1-0", UnitStatus.IN_STOCK, List.of(), 1, null, null));
    inventory.seedUnits("alice", sku.skuId(), units);
    pricing.seedPrice(
        "alice",
        "card-1",
        Condition.NM,
        new GetPriceResponse(new BigDecimal("1.23"), new BigDecimal("1.45")));
    var queue = new FakeQueueClient<RepricingMessage>();
    var handler = newHandler(queue, clock, inventory, pricing);

    // act
    handler.handleMessage(new RequestContext("alice"), new RepricingMessage("alice", "run-1"));

    // assert
    var completed = repricingRepository.get("alice");
    assertThat(completed.getStatus()).isEqualTo("succeeded");
    assertThat(completed.getProcessedSkuCount()).isEqualTo(1);
    assertThat(completed.getStartedAt()).isEqualTo(startedAt);
    assertThat(completed.getFinishedAt()).isEqualTo(startedAt);
    assertThat(
            inventory
                .getSku(new RequestContext("alice"), new GetSkuRequest(sku.skuId()))
                .sku()
                .price())
        .isEqualByComparingTo("1.45");
    assertThat(queue.getSends()).isEmpty();

    var unusedInventory = mock(InventoryService.class);
    var unusedPricing = mock(PricingService.class);
    newHandler(queue, clock, unusedInventory, unusedPricing)
        .handleMessage(new RequestContext("alice"), new RepricingMessage("alice", "run-1"));
    verifyNoInteractions(unusedInventory, unusedPricing);
  }

  @Test
  void messageHandlerShouldCheckpointEveryCandidateIncludingCandidatesWithoutStock()
      throws Exception {
    // arrange
    var clock = new FakeClock();
    repricingRepository.createRepricing("alice", "run-1", clock.now());
    var games = new FakeGamesService();
    var inventory = new FakeInventoryService(games);
    for (var index = 0; index < 101; index++) {
      var sku = sku("mtg#scryfall#card-" + index + "#normal#NM", "card-" + index);
      inventory.seedSku("alice", sku, false);
    }
    var queue = new FakeQueueClient<RepricingMessage>();
    var pricing = new FakePricingService();
    var handler = newHandler(queue, clock, inventory, pricing);
    var ctx = new RequestContext("alice");
    var message = new RepricingMessage("alice", "run-1");

    // act
    handler.handleMessage(ctx, message);

    // assert first page is checkpointed and dispatched
    var checkpoint = repricingRepository.get("alice");
    assertThat(checkpoint.getStatus()).isEqualTo("running");
    assertThat(checkpoint.getProcessedSkuCount()).isEqualTo(100);
    assertThat(checkpoint.getContinuation()).isEqualTo("100");
    assertThat(queue.getSends())
        .containsExactly(new FakeQueueClient.SentMessage<>(message, "run-1", "run-1#100"));

    // act on the next delivery
    handler.handleMessage(ctx, message);

    // assert
    var completed = repricingRepository.get("alice");
    assertThat(completed.getStatus()).isEqualTo("succeeded");
    assertThat(completed.getProcessedSkuCount()).isEqualTo(101);
    assertThat(completed.getContinuation()).isNull();
    assertThat(queue.getSends()).hasSize(1);
  }

  @Test
  void messageHandlerShouldCompleteAnEmptyRunWithoutDispatching() throws Exception {
    // arrange
    var clock = new FakeClock();
    repricingRepository.createRepricing("alice", "run-1", clock.now());
    var games = new FakeGamesService();
    var inventory = new FakeInventoryService(games);
    var queue = new FakeQueueClient<RepricingMessage>();
    var handler = newHandler(queue, clock, inventory, new FakePricingService());

    // act
    handler.handleMessage(new RequestContext("alice"), new RepricingMessage("alice", "run-1"));

    // assert
    var completed = repricingRepository.get("alice");
    assertThat(completed.getStatus()).isEqualTo("succeeded");
    assertThat(completed.getProcessedSkuCount()).isZero();
    assertThat(queue.getSends()).isEmpty();
  }

  @Test
  void messageHandlerShouldLeavePageUncheckpointedWhenLaterCandidateFails() throws Exception {
    // arrange
    var clock = new FakeClock();
    repricingRepository.createRepricing("alice", "run-1", clock.now());
    var inventory = mock(InventoryService.class);
    var first = sku("mtg#scryfall#first#normal#NM", "first");
    var second = sku("mtg#scryfall#second#normal#NM", "second");
    var failSecondCandidate = new AtomicBoolean(true);
    when(inventory.findSkus(any(), any()))
        .thenReturn(new FindSkusResponse(List.of(first, second), null));
    when(inventory.getSku(any(), any()))
        .thenAnswer(
            invocation -> {
              var request = (GetSkuRequest) invocation.getArgument(1);
              return new GetSkuResponse(request.skuId().equals(first.skuId()) ? first : second);
            });
    when(inventory.findUnits(any(), any()))
        .thenAnswer(
            invocation -> {
              var request = (FindUnitsRequest) invocation.getArgument(1);
              if (request.skuId().equals(second.skuId())) {
                if (failSecondCandidate.getAndSet(false)) {
                  throw new BadRequestException("later candidate failed");
                }
                return new FindUnitsResponse(List.of(), null);
              }
              return new FindUnitsResponse(
                  List.of(new Unit(1, "A0-1", UnitStatus.IN_STOCK, List.of(), 1, null, null)),
                  null);
            });
    var pricing = mock(PricingService.class);
    when(pricing.getPrice(any(), any()))
        .thenReturn(new GetPriceResponse(BigDecimal.ONE, new BigDecimal("1.25")));
    var queue = new FakeQueueClient<RepricingMessage>();
    var handler = newHandler(queue, clock, inventory, pricing);

    // act / assert
    assertThatThrownBy(
            () ->
                handler.handleMessage(
                    new RequestContext("alice"), new RepricingMessage("alice", "run-1")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("later candidate failed");
    assertThat(repricingRepository.get("alice").getStatus()).isEqualTo("running");
    assertThat(repricingRepository.get("alice").getProcessedSkuCount()).isZero();
    assertThat(queue.getSends()).isEmpty();
    verify(inventory).updateSku(any(), any());

    // act on retry
    handler.handleMessage(new RequestContext("alice"), new RepricingMessage("alice", "run-1"));

    // assert
    assertThat(repricingRepository.get("alice").getStatus()).isEqualTo("succeeded");
    assertThat(repricingRepository.get("alice").getProcessedSkuCount()).isEqualTo(2);
    verify(inventory, times(2)).updateSku(any(), any());
  }

  @Test
  void messageHandlerShouldRejectMissingAndIncompleteRunMetadata() throws Exception {
    // arrange
    var clock = new FakeClock();
    var inventory = mock(InventoryService.class);
    var pricing = mock(PricingService.class);
    var queue = new FakeQueueClient<RepricingMessage>();
    var handler = newHandler(queue, clock, inventory, pricing);

    // act / assert missing
    assertThatThrownBy(
            () ->
                handler.handleMessage(
                    new RequestContext("alice"), new RepricingMessage("alice", "run-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Repricing run metadata is missing or incomplete");

    // arrange incomplete persisted record
    dynamoDbClient.putItem(
        PutItemRequest.builder()
            .tableName(TABLE_NAME)
            .item(
                Map.of(
                    RepricingItem.PK, AttributeValue.fromS(RepricingItem.formatPk("alice")),
                    RepricingItem.SK, AttributeValue.fromS(RepricingItem.formatSk()),
                    RepricingItem.USER, AttributeValue.fromS("alice"),
                    RepricingItem.REPRICING_ID, AttributeValue.fromS("run-1"),
                    RepricingItem.STATUS, AttributeValue.fromS("queued"),
                    RepricingItem.STARTED_AT, AttributeValue.fromN("100"),
                    RepricingItem.UPDATED_AT, AttributeValue.fromN("100")))
            .build());

    // act / assert
    assertThatThrownBy(
            () ->
                handler.handleMessage(
                    new RequestContext("alice"), new RepricingMessage("alice", "run-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Repricing run metadata is missing or incomplete");
    verifyNoInteractions(inventory, pricing);
    assertThat(queue.getSends()).isEmpty();
  }

  @Test
  void messageHandlerShouldNotDispatchWhenCheckpointFails() throws Exception {
    // arrange
    var clock = new FakeClock();
    repricingRepository.createRepricing("alice", "run-1", clock.now());
    repricingRepository.startRepricing("alice", "run-1", clock.now());
    var failure = new IllegalStateException("checkpoint failed");
    var dynamoDbClient = mock(DynamoDbClient.class);
    when(dynamoDbClient.updateItem(any(UpdateItemRequest.class))).thenThrow(failure);
    var failingRepository = new RepricingRepository(repricingTable, dynamoDbClient, TABLE_NAME);
    var inventory = mock(InventoryService.class);
    var sku = sku("mtg#scryfall#card-1#normal#NM", "card-1");
    when(inventory.findSkus(any(), any()))
        .thenReturn(new FindSkusResponse(List.of(sku), "opaque-next"));
    when(inventory.getSku(any(), any())).thenReturn(new GetSkuResponse(sku));
    when(inventory.findUnits(any(), any())).thenReturn(new FindUnitsResponse(List.of(), null));
    var queue = mock(QueueClient.class);
    var handler =
        new RepricingMessageHandler(
            failingRepository, queue, clock, inventory, new FakePricingService());

    // act / assert
    assertThatThrownBy(
            () ->
                handler.handleMessage(
                    new RequestContext("alice"), new RepricingMessage("alice", "run-1")))
        .isSameAs(failure);
    assertThat(repricingRepository.get("alice").getProcessedSkuCount()).isZero();
    verify(queue, never()).send(any(), any(), any());
  }

  @Test
  void messageHandlerShouldPreserveCheckpointWhenContinuationDispatchFails() throws Exception {
    // arrange
    var clock = new FakeClock();
    repricingRepository.createRepricing("alice", "run-1", clock.now());
    repricingRepository.startRepricing("alice", "run-1", clock.now());
    var inventory = mock(InventoryService.class);
    var sku = sku("mtg#scryfall#card-1#normal#NM", "card-1");
    when(inventory.findSkus(any(), any()))
        .thenReturn(new FindSkusResponse(List.of(sku), "opaque-next"));
    when(inventory.getSku(any(), any())).thenReturn(new GetSkuResponse(sku));
    when(inventory.findUnits(any(), any())).thenReturn(new FindUnitsResponse(List.of(), null));
    var failure = new IllegalStateException("dispatch failed");
    var queue = mock(QueueClient.class);
    doThrow(failure).when(queue).send(any(), any(), any());
    var handler = newHandler(queue, clock, inventory, new FakePricingService());

    // act / assert
    assertThatThrownBy(
            () ->
                handler.handleMessage(
                    new RequestContext("alice"), new RepricingMessage("alice", "run-1")))
        .isSameAs(failure);
    var checkpoint = repricingRepository.get("alice");
    assertThat(checkpoint.getProcessedSkuCount()).isEqualTo(1);
    assertThat(checkpoint.getContinuation()).isEqualTo("opaque-next");
  }

  @Test
  void messageHandlerShouldIgnoreSupersededAndSucceededMessages() throws Exception {
    // arrange
    var clock = new FakeClock();
    repricingRepository.createRepricing("alice", "old-run", clock.now());
    repricingRepository.startRepricing("alice", "old-run", clock.now());
    repricingRepository.completeRepricing("alice", "old-run", 0, 0, clock.now());
    repricingRepository.createRepricing("alice", "current-run", clock.now());
    var inventory = mock(InventoryService.class);
    var pricing = mock(PricingService.class);
    var queue = mock(QueueClient.class);
    var handler = newHandler(queue, clock, inventory, pricing);

    // act
    handler.handleMessage(new RequestContext("alice"), new RepricingMessage("alice", "old-run"));

    // assert
    verify(inventory, never()).findSkus(any(), any());
    verify(queue, never()).send(any(), any(), any());
  }

  @Test
  void messageShouldUseExplicitJsonFields() throws Exception {
    // arrange
    var objectMapper = new ObjectMapper();

    // act
    var json = objectMapper.writeValueAsString(new RepricingMessage("alice", "run-1"));

    // assert
    assertThat(json).isEqualTo("{\"user\":\"alice\",\"repricing_id\":\"run-1\"}");
    assertThat(objectMapper.readValue(json, RepricingMessage.class))
        .isEqualTo(new RepricingMessage("alice", "run-1"));
  }

  private RepricingMessageHandler newHandler(
      QueueClient<RepricingMessage> queue,
      FakeClock clock,
      InventoryService inventory,
      PricingService pricing) {
    return new RepricingMessageHandler(repricingRepository, queue, clock, inventory, pricing);
  }

  private Sku sku(String skuId, String cardId) {
    return new Sku(
        skuId,
        "mtg",
        cardId,
        "Card " + cardId,
        "SET",
        "Set",
        "1",
        "normal",
        Condition.NM,
        new BigDecimal("1.00"),
        cardId,
        1,
        0);
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
        RepricingItem.PK, AttributeValue.fromS(RepricingItem.formatPk(user)),
        RepricingItem.SK, AttributeValue.fromS(RepricingItem.formatSk()));
  }
}
