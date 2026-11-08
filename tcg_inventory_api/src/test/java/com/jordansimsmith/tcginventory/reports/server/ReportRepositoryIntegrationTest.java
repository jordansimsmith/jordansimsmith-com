package com.jordansimsmith.tcginventory.reports.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.jordansimsmith.tcginventory.games.client.FindGamesRequest;
import com.jordansimsmith.tcginventory.games.client.GamesService;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FakeInventoryService;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusResponse;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsResponse;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.orders.client.FindOrdersResponse;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import com.jordansimsmith.tcginventory.orders.client.OrderState;
import com.jordansimsmith.tcginventory.orders.client.OrderSummary;
import com.jordansimsmith.tcginventory.orders.client.OrderSummaryRow;
import com.jordansimsmith.time.Clock;
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
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@Testcontainers
class ReportRepositoryIntegrationTest {
  private static final String TABLE_NAME = "reports_test";
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbEnhancedClient dynamoDbEnhancedClient;
  private static DynamoDbTable<ReportItem> reportTable;
  private ReportRepository reportRepository;

  @BeforeAll
  static void setUpBeforeClass() {
    dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .build();
    dynamoDbEnhancedClient =
        DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    reportTable = dynamoDbEnhancedClient.table(TABLE_NAME, TableSchema.fromBean(ReportItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, reportTable);
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    reportRepository = new ReportRepository(reportTable, dynamoDbClient, TABLE_NAME);
  }

  @Test
  void createGenerationShouldCreateExactRecordWithNumericTimestamp() {
    // arrange
    var startedAt = Instant.ofEpochSecond(1_700_000_001L);

    // act
    reportRepository.createGeneration("alice", "generation-1", startedAt);

    // assert
    var raw = getRaw("alice");
    assertThat(raw)
        .containsEntry(ReportItem.PK, AttributeValue.fromS("USER#alice"))
        .containsEntry(ReportItem.SK, AttributeValue.fromS("REPORT"))
        .containsEntry(ReportItem.USER, AttributeValue.fromS("alice"))
        .containsEntry(ReportItem.GENERATION_ID, AttributeValue.fromS("generation-1"))
        .containsEntry(ReportItem.GENERATION_STATUS, AttributeValue.fromS("queued"))
        .containsEntry(ReportItem.GENERATION_STARTED_AT, AttributeValue.fromN("1700000001"));
    assertThat(raw).doesNotContainKeys(ReportItem.REPORT, ReportItem.UPDATED_AT);
    assertThat(reportRepository.get("alice").getGenerationStartedAt()).isEqualTo(startedAt);
    assertThat(reportRepository.get("bob")).isNull();
  }

  @Test
  void generationUpdatesShouldPreserveSnapshotAndAtomicallyComplete() {
    // arrange
    var previousUpdatedAt = Instant.ofEpochSecond(1_700_000_000L);
    reportTable.putItem(legacySnapshot("alice", "previous-report", previousUpdatedAt));
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(TABLE_NAME)
            .key(key("alice"))
            .updateExpression("SET #extra = :extra")
            .expressionAttributeNames(Map.of("#extra", "extra"))
            .expressionAttributeValues(Map.of(":extra", AttributeValue.fromS("preserve-me")))
            .build());
    var startedAt = Instant.ofEpochSecond(1_700_000_001L);
    var generatedAt = Instant.ofEpochSecond(1_700_000_010L);
    var finishedAt = Instant.ofEpochSecond(1_700_000_020L);

    // act
    reportRepository.createGeneration("alice", "generation-1", startedAt);
    var queued = reportRepository.get("alice");
    reportRepository.startGeneration("alice", "generation-1");
    reportRepository.completeGeneration(
        "alice", "generation-1", "new-report", generatedAt, finishedAt);

    // assert
    assertThat(queued.getReport()).isEqualTo("previous-report");
    assertThat(queued.getUpdatedAt()).isEqualTo(previousUpdatedAt);
    var completed = reportRepository.get("alice");
    assertThat(completed.getReport()).isEqualTo("new-report");
    assertThat(completed.getUpdatedAt()).isEqualTo(generatedAt);
    assertThat(completed.getGenerationStatus()).isEqualTo("succeeded");
    assertThat(completed.getGenerationFinishedAt()).isEqualTo(finishedAt);
    assertThat(getRaw("alice"))
        .containsEntry(ReportItem.UPDATED_AT, AttributeValue.fromN("1700000010"))
        .containsEntry(ReportItem.GENERATION_FINISHED_AT, AttributeValue.fromN("1700000020"))
        .containsEntry("extra", AttributeValue.fromS("preserve-me"));
  }

  @Test
  void createGenerationAfterSuccessShouldRetainSnapshotAndRemoveFinishedAt() {
    // arrange
    var startedAt = Instant.ofEpochSecond(1_700_000_001L);
    reportRepository.createGeneration("alice", "generation-1", startedAt);
    reportRepository.startGeneration("alice", "generation-1");
    reportRepository.completeGeneration(
        "alice",
        "generation-1",
        "saved-report",
        Instant.ofEpochSecond(1_700_000_010L),
        Instant.ofEpochSecond(1_700_000_020L));

    // act
    reportRepository.createGeneration(
        "alice", "generation-2", Instant.ofEpochSecond(1_700_000_030L));

    // assert
    var item = reportRepository.get("alice");
    assertThat(item.getReport()).isEqualTo("saved-report");
    assertThat(item.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_010L));
    assertThat(item.getGenerationId()).isEqualTo("generation-2");
    assertThat(item.getGenerationStatus()).isEqualTo("queued");
    assertThat(item.getGenerationFinishedAt()).isNull();
  }

  @Test
  void repositoryShouldReadLegacySnapshotWithoutGenerationMetadata() {
    // arrange
    reportTable.putItem(legacySnapshot("alice", "legacy-report", Instant.ofEpochSecond(42)));

    // act
    var item = reportRepository.get("alice");

    // assert
    assertThat(item.getReport()).isEqualTo("legacy-report");
    assertThat(item.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(42));
    assertThat(item.getUser()).isNull();
    assertThat(item.getGenerationId()).isNull();
    assertThat(item.getGenerationStatus()).isNull();
    assertThat(item.getGenerationStartedAt()).isNull();
    assertThat(item.getGenerationFinishedAt()).isNull();
  }

  @Test
  void generationMutationsShouldRejectWrongIdentityAndInvalidTransitions() {
    // arrange
    reportRepository.createGeneration("alice", "generation-1", Instant.ofEpochSecond(1));

    // act / assert
    assertThatThrownBy(() -> reportRepository.startGeneration("alice", "wrong-id"))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThatThrownBy(
            () ->
                reportRepository.completeGeneration(
                    "alice",
                    "generation-1",
                    "bad",
                    Instant.ofEpochSecond(2),
                    Instant.ofEpochSecond(3)))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThatThrownBy(
            () ->
                reportRepository.createGeneration(
                    "alice", "generation-2", Instant.ofEpochSecond(4)))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThat(reportRepository.get("alice").getGenerationId()).isEqualTo("generation-1");
    assertThat(reportRepository.get("alice").getGenerationStatus()).isEqualTo("queued");
  }

  @Test
  void repositoryShouldPropagateStorageFailures() {
    // arrange
    var failure = new IllegalStateException("storage failed");
    var table = mock(DynamoDbTable.class);
    when(table.getItem(any(GetItemEnhancedRequest.class))).thenThrow(failure);
    var readFailingRepository = new ReportRepository(table, dynamoDbClient, TABLE_NAME);

    // act / assert
    assertThatThrownBy(() -> readFailingRepository.get("alice")).isSameAs(failure);

    var client = mock(DynamoDbClient.class);
    when(client.updateItem(any(UpdateItemRequest.class))).thenThrow(failure);
    var failingRepository = new ReportRepository(reportTable, client, TABLE_NAME);
    assertThatThrownBy(
            () -> failingRepository.createGeneration("alice", "generation-1", Instant.EPOCH))
        .isSameAs(failure);
  }

  @Test
  void messageHandlerShouldBuildAndCompleteReport() throws Exception {
    // arrange
    var user = "alice";
    var ctx = new RequestContext(user);
    reportRepository.createGeneration(user, "generation-1", Instant.ofEpochSecond(1_700_000_001));
    var clock = mock(Clock.class);
    when(clock.now())
        .thenReturn(Instant.ofEpochSecond(1_700_000_010), Instant.ofEpochSecond(1_700_000_020));
    var games = new FakeGamesService();
    var inventory = new FakeInventoryService(games);
    var sku =
        new Sku(
            "sku-1",
            "mtg",
            "card-1",
            "Card One",
            "SET",
            "Set Name",
            "1",
            "normal",
            Condition.NM,
            new BigDecimal("2.50"),
            null,
            1,
            0);
    inventory.seedSku(user, sku, false);
    inventory.seedUnits(
        user,
        sku.skuId(),
        List.of(new Unit(0, "A0-0", UnitStatus.IN_STOCK, List.of(), 1_700_000_000, null, null)));
    var pokemonSku =
        new Sku(
            "pokemon-sku-1",
            "pokemon",
            "product-1",
            "Pokémon One",
            "PSET",
            "Pokémon Set",
            "1",
            "normal",
            Condition.NM,
            new BigDecimal("1.25"),
            null,
            1,
            0);
    inventory.seedSku(user, pokemonSku, false);
    inventory.seedUnits(
        user,
        pokemonSku.skuId(),
        List.of(new Unit(0, "A0-0", UnitStatus.IN_STOCK, List.of(), 1_700_000_000, null, null)));
    var orderService = mock(OrderService.class);
    when(orderService.findOrders(any(), any()))
        .thenReturn(
            new FindOrdersResponse(
                List.of(
                    new OrderSummary(
                        "order-1",
                        OrderState.FULFILLED,
                        1_700_000_000,
                        null,
                        "3.50",
                        "3.50",
                        null,
                        1,
                        List.of(new OrderSummaryRow(sku.skuId(), "3.50")))),
                null));
    var handler =
        new ReportMessageHandler(
            reportRepository, new ObjectMapper(), clock, games, inventory, orderService);

    // act
    handler.handleMessage(ctx, new ReportMessage(user, "generation-1"));

    // assert
    var completed = reportRepository.get(user);
    assertThat(completed.getGenerationStatus()).isEqualTo("succeeded");
    assertThat(completed.getGenerationStartedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_001));
    assertThat(completed.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_010));
    assertThat(completed.getGenerationFinishedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_020));
    var payload = new ObjectMapper().readTree(completed.getReport());
    assertThat(payload.at("/totals/inventory_value").asText()).isEqualTo("3.75");
    assertThat(payload.at("/totals/revenue_to_date").asText()).isEqualTo("3.50");
    assertThat(payload.at("/games/0/game").asText()).isEqualTo("mtg");
    assertThat(payload.at("/games/0/totals/inventory_value").asText()).isEqualTo("2.50");
    assertThat(payload.at("/games/1/game").asText()).isEqualTo("pokemon");
    assertThat(payload.at("/games/1/totals/inventory_value").asText()).isEqualTo("1.25");
  }

  @Test
  void messageHandlerShouldFollowEmptyIntermediatePages() throws Exception {
    // arrange
    var user = "alice";
    var ctx = new RequestContext(user);
    reportRepository.createGeneration(user, "generation-1", Instant.EPOCH);
    var clock = mock(Clock.class);
    when(clock.now()).thenReturn(Instant.ofEpochSecond(10), Instant.ofEpochSecond(11));
    var inventory = mock(InventoryService.class);
    var sku =
        new Sku(
            "sku-1",
            "mtg",
            "card-1",
            "Card One",
            "SET",
            "Set Name",
            "1",
            "normal",
            Condition.NM,
            BigDecimal.ONE,
            null,
            1,
            0);
    when(inventory.findSkus(any(), any()))
        .thenReturn(new FindSkusResponse(List.of(), "sku-empty"))
        .thenReturn(new FindSkusResponse(List.of(sku), null));
    when(inventory.findUnits(any(), any()))
        .thenReturn(new FindUnitsResponse(List.of(), "unit-empty"))
        .thenReturn(
            new FindUnitsResponse(
                List.of(new Unit(0, "A0-0", UnitStatus.IN_STOCK, List.of(), 1, null, null)), null));
    var orderService = mock(OrderService.class);
    when(orderService.findOrders(any(), any()))
        .thenReturn(new FindOrdersResponse(List.of(), "order-empty"))
        .thenReturn(new FindOrdersResponse(List.of(), null));
    var handler =
        new ReportMessageHandler(
            reportRepository,
            new ObjectMapper(),
            clock,
            new FakeGamesService(),
            inventory,
            orderService);

    // act
    handler.handleMessage(ctx, new ReportMessage(user, "generation-1"));

    // assert
    assertThat(reportRepository.get(user).getGenerationStatus()).isEqualTo("succeeded");
    verify(inventory, times(2)).findSkus(any(), any());
    verify(inventory, times(2)).findUnits(any(), any());
    verify(orderService, times(2)).findOrders(any(), any());
  }

  @Test
  void messageHandlerShouldIgnoreSupersededAndCompletedMessages() throws Exception {
    // arrange
    var user = "alice";
    var ctx = new RequestContext(user);
    reportRepository.createGeneration(user, "generation-1", Instant.EPOCH);
    reportRepository.startGeneration(user, "generation-1");
    reportRepository.completeGeneration(
        user, "generation-1", "saved-report", Instant.ofEpochSecond(1), Instant.ofEpochSecond(2));
    reportRepository.createGeneration(user, "generation-2", Instant.ofEpochSecond(3));
    var games = mock(GamesService.class);
    var inventory = mock(InventoryService.class);
    var orders = mock(OrderService.class);
    when(games.findGames(any(), any()))
        .thenReturn(new FakeGamesService().findGames(ctx, new FindGamesRequest()));
    when(inventory.findSkus(any(), any())).thenReturn(new FindSkusResponse(List.of(), null));
    when(orders.findOrders(any(), any())).thenReturn(new FindOrdersResponse(List.of(), null));
    var clock = mock(Clock.class);
    when(clock.now()).thenReturn(Instant.ofEpochSecond(4), Instant.ofEpochSecond(5));
    var handler =
        new ReportMessageHandler(
            reportRepository, new ObjectMapper(), clock, games, inventory, orders);

    // act
    handler.handleMessage(ctx, new ReportMessage(user, "generation-1"));
    verifyNoInteractions(games, inventory, orders);
    handler.handleMessage(ctx, new ReportMessage(user, "generation-2"));
    handler.handleMessage(ctx, new ReportMessage(user, "generation-2"));

    // assert
    assertThat(reportRepository.get(user).getGenerationStatus()).isEqualTo("succeeded");
    assertThat(reportRepository.get(user).getGenerationId()).isEqualTo("generation-2");
    verify(games, times(1)).findGames(any(), any());
    verify(inventory, times(1)).findSkus(any(), any());
    verify(orders, times(1)).findOrders(any(), any());
  }

  @Test
  void messageHandlerShouldKeepPreviousSnapshotAfterLaterPageFailureAndRetry() throws Exception {
    // arrange
    var user = "alice";
    var ctx = new RequestContext(user);
    reportTable.putItem(legacySnapshot(user, "old-report", Instant.ofEpochSecond(1)));
    reportRepository.createGeneration(user, "generation-1", Instant.ofEpochSecond(2));
    reportRepository.startGeneration(user, "generation-1");
    var clock = mock(Clock.class);
    when(clock.now())
        .thenReturn(Instant.ofEpochSecond(3), Instant.ofEpochSecond(4), Instant.ofEpochSecond(5));
    var failure = new ServiceUnavailableException("Orders are temporarily unavailable");
    var orderService = mock(OrderService.class);
    when(orderService.findOrders(any(), any()))
        .thenReturn(new FindOrdersResponse(List.of(), "next"))
        .thenThrow(failure)
        .thenReturn(new FindOrdersResponse(List.of(), null));
    var handler =
        new ReportMessageHandler(
            reportRepository,
            new ObjectMapper(),
            clock,
            new FakeGamesService(),
            new FakeInventoryService(new FakeGamesService()),
            orderService);
    var message = new ReportMessage(user, "generation-1");

    // act / assert
    assertThatThrownBy(() -> handler.handleMessage(ctx, message)).isSameAs(failure);
    assertThat(reportRepository.get(user).getGenerationStatus()).isEqualTo("running");
    assertThat(reportRepository.get(user).getReport()).isEqualTo("old-report");

    // act
    handler.handleMessage(ctx, message);

    // assert
    assertThat(reportRepository.get(user).getGenerationStatus()).isEqualTo("succeeded");
    assertThat(reportRepository.get(user).getReport()).isNotEqualTo("old-report");
  }

  @Test
  void messageHandlerShouldFailWhenGenerationMetadataIsInvalid() {
    // arrange
    var handler =
        new ReportMessageHandler(
            reportRepository,
            new ObjectMapper(),
            mock(Clock.class),
            mock(GamesService.class),
            mock(InventoryService.class),
            mock(OrderService.class));

    // act / assert
    assertThatThrownBy(
            () ->
                handler.handleMessage(
                    new RequestContext("alice"), new ReportMessage("alice", "generation-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Report generation metadata is missing or incomplete");

    // arrange
    var item = new ReportItem();
    item.setPk(ReportItem.formatPk("alice"));
    item.setSk(ReportItem.formatSk());
    item.setGenerationId("generation-1");
    item.setGenerationStatus("failed");
    item.setGenerationStartedAt(Instant.EPOCH);
    reportTable.putItem(item);

    // act / assert
    assertThatThrownBy(
            () ->
                handler.handleMessage(
                    new RequestContext("alice"), new ReportMessage("alice", "generation-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Report generation has an unknown status");
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

  private static Map<String, AttributeValue> key(String user) {
    return Map.of(
        ReportItem.PK, AttributeValue.fromS(ReportItem.formatPk(user)),
        ReportItem.SK, AttributeValue.fromS(ReportItem.formatSk()));
  }

  private static ReportItem legacySnapshot(String user, String report, Instant updatedAt) {
    var item = new ReportItem();
    item.setPk(ReportItem.formatPk(user));
    item.setSk(ReportItem.formatSk());
    item.setReport(report);
    item.setUpdatedAt(updatedAt);
    return item;
  }
}
