package com.jordansimsmith.ankibackup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@Testcontainers
public class UpdateBackupHandlerIntegrationTest {
  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<AnkiBackupItem> ankiBackupTable;

  private UpdateBackupHandler updateBackupHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static final URI UNUSED_S3_ENDPOINT = URI.create("http://localhost:1");

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = AnkiBackupTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.ankiBackupTable());
  }

  @BeforeEach
  void setUp() {
    var factory = AnkiBackupTestFactory.create(dynamoDbContainer.getEndpoint(), UNUSED_S3_ENDPOINT);

    fakeClock = factory.fakeClock();
    objectMapper = factory.objectMapper();
    ankiBackupTable = factory.ankiBackupTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    updateBackupHandler = new UpdateBackupHandler(factory);
  }

  private APIGatewayV2HTTPEvent buildEvent(String user, String backupId, String body) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withPathParameters(Map.of("backup_id", backupId))
        .withBody(body)
        .build();
  }

  private AnkiBackupItem createPendingBackup(String user, String backupId, Instant createdAt) {
    var item = new AnkiBackupItem();
    item.setPk(AnkiBackupItem.formatPk(user));
    item.setSk(AnkiBackupItem.formatSk(backupId));
    item.setBackupId(backupId);
    item.setStatus(AnkiBackupItem.STATUS_PENDING);
    item.setProfileId("japanese-main");
    item.setS3Bucket(UpdateBackupHandler.BUCKET);
    item.setS3Key(
        "users/" + user + "/profiles/japanese-main/backups/2026/03/01/" + backupId + ".colpkg");
    item.setUploadId("upload-" + backupId);
    item.setPartSizeBytes(CreateBackupHandler.PART_SIZE_BYTES);
    item.setSizeBytes(1024L);
    item.setSha256("sha256hash");
    item.setCreatedAt(createdAt);
    item.setExpiresAt(createdAt.plus(Duration.ofDays(90)));
    item.setTtl(createdAt.plus(Duration.ofDays(90)).getEpochSecond());
    ankiBackupTable.putItem(item);
    return item;
  }

  @Test
  void handleRequestShouldReturnNotFoundWhenBackupDoesNotExist() throws Exception {
    // arrange
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    var body =
        objectMapper.writeValueAsString(new UpdateBackupHandler.UpdateBackupRequest("COMPLETED"));
    var event = buildEvent("alice", "nonexistent-id", body);

    // act
    var res = updateBackupHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(404);
    var tree = objectMapper.readTree(res.getBody());
    assertThat(tree.get("message").asText()).isEqualTo("backup not found");
  }

  @Test
  void handleRequestShouldReturnNotFoundWhenBackupBelongsToDifferentUser() throws Exception {
    // arrange
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    createPendingBackup("bob", "backup-123", now.minus(Duration.ofMinutes(5)));

    var body =
        objectMapper.writeValueAsString(new UpdateBackupHandler.UpdateBackupRequest("COMPLETED"));
    var event = buildEvent("alice", "backup-123", body);

    // act
    var res = updateBackupHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(404);
    var tree = objectMapper.readTree(res.getBody());
    assertThat(tree.get("message").asText()).isEqualTo("backup not found");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenBackupAlreadyCompleted() throws Exception {
    // arrange
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    var item = createPendingBackup("alice", "backup-456", now.minus(Duration.ofMinutes(10)));
    item.setStatus(AnkiBackupItem.STATUS_COMPLETED);
    item.setCompletedAt(now.minus(Duration.ofMinutes(5)));
    ankiBackupTable.updateItem(item);

    var body =
        objectMapper.writeValueAsString(new UpdateBackupHandler.UpdateBackupRequest("COMPLETED"));
    var event = buildEvent("alice", "backup-456", body);

    // act
    var res = updateBackupHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(400);
    var tree = objectMapper.readTree(res.getBody());
    assertThat(tree.get("message").asText()).isEqualTo("backup already completed");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenStatusIsNotCompleted() throws Exception {
    // arrange
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    var body =
        objectMapper.writeValueAsString(new UpdateBackupHandler.UpdateBackupRequest("INVALID"));
    var event = buildEvent("alice", "backup-789", body);

    // act
    var res = updateBackupHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(400);
    var tree = objectMapper.readTree(res.getBody());
    assertThat(tree.get("message").asText()).isEqualTo("status must be COMPLETED");
  }

  @Test
  void handleRequestShouldNotModifyBackupOfDifferentUserWhenRejected() throws Exception {
    // arrange
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    var createdAt = now.minus(Duration.ofMinutes(5));
    createPendingBackup("bob", "backup-xyz", createdAt);

    var body =
        objectMapper.writeValueAsString(new UpdateBackupHandler.UpdateBackupRequest("COMPLETED"));
    var event = buildEvent("alice", "backup-xyz", body);

    // act
    var res = updateBackupHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(404);
    var dbItem =
        ankiBackupTable.getItem(
            Key.builder()
                .partitionValue(AnkiBackupItem.formatPk("bob"))
                .sortValue(AnkiBackupItem.formatSk("backup-xyz"))
                .build());
    assertThat(dbItem.getStatus()).isEqualTo(AnkiBackupItem.STATUS_PENDING);
    assertThat(dbItem.getCompletedAt()).isNull();
  }

  @Test
  void handleRequestShouldProceedToS3WhenPendingBackupExists() throws Exception {
    // arrange
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    createPendingBackup("alice", "backup-ok", now.minus(Duration.ofMinutes(5)));

    var body =
        objectMapper.writeValueAsString(new UpdateBackupHandler.UpdateBackupRequest("COMPLETED"));
    var event = buildEvent("alice", "backup-ok", body);

    // act + assert
    // handler passes validation and proceeds to S3 ListParts which fails (no real S3 endpoint)
    assertThatThrownBy(() -> updateBackupHandler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class);
  }
}
