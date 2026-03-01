package com.jordansimsmith.ankibackup;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.s3.S3Container;
import com.jordansimsmith.time.FakeClock;
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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

@Testcontainers
public class UpdateBackupHandlerIntegrationTest {
  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<AnkiBackupItem> ankiBackupTable;
  private S3Client s3Client;

  private UpdateBackupHandler updateBackupHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();
  @Container private static final S3Container s3Container = new S3Container();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        AnkiBackupTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.ankiBackupTable());
    factory.s3Client().createBucket(b -> b.bucket(UpdateBackupHandler.BUCKET));
  }

  @BeforeEach
  void setUp() {
    var factory =
        AnkiBackupTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());

    fakeClock = factory.fakeClock();
    objectMapper = factory.objectMapper();
    ankiBackupTable = factory.ankiBackupTable();
    s3Client = factory.s3Client();

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

  private AnkiBackupItem createPendingBackup(
      String user, String backupId, Instant createdAt, String uploadId) {
    var s3Key =
        "users/" + user + "/profiles/japanese-main/backups/2026/03/01/" + backupId + ".colpkg";
    var item = new AnkiBackupItem();
    item.setPk(AnkiBackupItem.formatPk(user));
    item.setSk(AnkiBackupItem.formatSk(backupId));
    item.setBackupId(backupId);
    item.setStatus(AnkiBackupItem.STATUS_PENDING);
    item.setProfileId("japanese-main");
    item.setS3Bucket(UpdateBackupHandler.BUCKET);
    item.setS3Key(s3Key);
    item.setUploadId(uploadId);
    item.setPartSizeBytes(CreateBackupHandler.PART_SIZE_BYTES);
    item.setSizeBytes(1024L);
    item.setSha256("sha256hash");
    item.setCreatedAt(createdAt);
    item.setExpiresAt(createdAt.plus(Duration.ofDays(90)));
    item.setTtl(createdAt.plus(Duration.ofDays(90)).getEpochSecond());
    ankiBackupTable.putItem(item);
    return item;
  }

  private String startMultipartUploadAndUploadPart(String s3Key, byte[] data) {
    var multipart =
        s3Client.createMultipartUpload(
            CreateMultipartUploadRequest.builder()
                .bucket(UpdateBackupHandler.BUCKET)
                .key(s3Key)
                .build());
    var uploadId = multipart.uploadId();

    s3Client.uploadPart(
        UploadPartRequest.builder()
            .bucket(UpdateBackupHandler.BUCKET)
            .key(s3Key)
            .uploadId(uploadId)
            .partNumber(1)
            .build(),
        RequestBody.fromBytes(data));

    return uploadId;
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

    var s3Key = "users/bob/profiles/japanese-main/backups/2026/03/01/backup-123.colpkg";
    var uploadId =
        startMultipartUploadAndUploadPart(s3Key, "test-data".getBytes(StandardCharsets.UTF_8));
    createPendingBackup("bob", "backup-123", now.minus(Duration.ofMinutes(5)), uploadId);

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

    var s3Key = "users/alice/profiles/japanese-main/backups/2026/03/01/backup-456.colpkg";
    var uploadId =
        startMultipartUploadAndUploadPart(s3Key, "test-data".getBytes(StandardCharsets.UTF_8));
    var item =
        createPendingBackup("alice", "backup-456", now.minus(Duration.ofMinutes(10)), uploadId);
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
    var s3Key = "users/bob/profiles/japanese-main/backups/2026/03/01/backup-xyz.colpkg";
    var uploadId =
        startMultipartUploadAndUploadPart(s3Key, "test-data".getBytes(StandardCharsets.UTF_8));
    createPendingBackup("bob", "backup-xyz", createdAt, uploadId);

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
  void handleRequestShouldCompleteBackupAndUpdateDynamoDb() throws Exception {
    // arrange
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    var s3Key = "users/alice/profiles/japanese-main/backups/2026/03/01/backup-ok.colpkg";
    var uploadId =
        startMultipartUploadAndUploadPart(s3Key, "backup-content".getBytes(StandardCharsets.UTF_8));
    createPendingBackup("alice", "backup-ok", now.minus(Duration.ofMinutes(5)), uploadId);

    var body =
        objectMapper.writeValueAsString(new UpdateBackupHandler.UpdateBackupRequest("COMPLETED"));
    var event = buildEvent("alice", "backup-ok", body);

    // act
    var res = updateBackupHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    var tree = objectMapper.readTree(res.getBody());
    assertThat(tree.get("status").asText()).isEqualTo("completed");

    var dbItem =
        ankiBackupTable.getItem(
            Key.builder()
                .partitionValue(AnkiBackupItem.formatPk("alice"))
                .sortValue(AnkiBackupItem.formatSk("backup-ok"))
                .build());
    assertThat(dbItem.getStatus()).isEqualTo(AnkiBackupItem.STATUS_COMPLETED);
    assertThat(dbItem.getCompletedAt()).isEqualTo(now);
  }
}
