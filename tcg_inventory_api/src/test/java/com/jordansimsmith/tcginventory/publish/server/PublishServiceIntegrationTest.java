package com.jordansimsmith.tcginventory.publish.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.tcginventory.publish.client.CreatePublishRequest;
import com.jordansimsmith.tcginventory.publish.client.CreatePublishResponse;
import com.jordansimsmith.tcginventory.publish.client.GetPublishRequest;
import com.jordansimsmith.tcginventory.publish.client.GetPublishResponse;
import com.jordansimsmith.tcginventory.publish.client.PublishStatus;
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
    publishRepository.checkpointPublish(USER, "publish-1", 0, 7, "7", checkpointAt);
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
    assertThat(completed.getContinuation()).isNull();
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
        .doesNotContainKey(PublishItem.CONTINUATION);
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
    publishRepository.checkpointPublish(USER, "publish-1", 0, 3, "3", Instant.ofEpochSecond(3));
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
    assertThat(publishRepository.get(USER).getContinuation()).isNull();
    assertThat(getRaw(USER)).containsEntry("other_attribute", AttributeValue.fromS("preserved"));
  }

  @Test
  void writesShouldRejectStaleIdsCountsAndInvalidTransitions() {
    publishRepository.createPublish(USER, "publish-1", Instant.ofEpochSecond(1));

    assertThatThrownBy(
            () -> publishRepository.startPublish(USER, "stale-id", Instant.ofEpochSecond(2)))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThatThrownBy(
            () -> publishRepository.checkpointPublish(USER, "publish-1", 0, 1, "1", Instant.now()))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThatThrownBy(
            () -> publishRepository.completePublish(USER, "publish-1", 0, 0, Instant.now()))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThatThrownBy(
            () -> publishRepository.checkpointPublish(USER, "publish-1", 0, 0, "0", Instant.now()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("checkpoint must advance published_sku_count");

    publishRepository.startPublish(USER, "publish-1", Instant.ofEpochSecond(2));
    assertThatThrownBy(
            () -> publishRepository.checkpointPublish(USER, "publish-1", 1, 2, "2", Instant.now()))
        .isInstanceOf(ConditionalCheckFailedException.class);
    publishRepository.checkpointPublish(USER, "publish-1", 0, 1, "1", Instant.ofEpochSecond(3));
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
