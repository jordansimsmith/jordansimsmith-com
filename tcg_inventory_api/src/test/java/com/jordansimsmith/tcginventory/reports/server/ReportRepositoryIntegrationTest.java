package com.jordansimsmith.tcginventory.reports.server;

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
