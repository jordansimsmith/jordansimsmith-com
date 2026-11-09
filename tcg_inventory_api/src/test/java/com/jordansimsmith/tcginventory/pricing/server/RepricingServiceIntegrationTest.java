package com.jordansimsmith.tcginventory.pricing.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.time.Instant;
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
