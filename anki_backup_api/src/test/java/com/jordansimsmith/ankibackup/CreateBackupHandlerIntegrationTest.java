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
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class CreateBackupHandlerIntegrationTest {
  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<AnkiBackupItem> ankiBackupTable;

  private CreateBackupHandler createBackupHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();
  @Container private static final S3Container s3Container = new S3Container();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        AnkiBackupTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.ankiBackupTable());
    factory.s3Client().createBucket(b -> b.bucket(CreateBackupHandler.BUCKET));
  }

  @BeforeEach
  void setUp() {
    var factory =
        AnkiBackupTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());

    fakeClock = factory.fakeClock();
    objectMapper = factory.objectMapper();
    ankiBackupTable = factory.ankiBackupTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    createBackupHandler = new CreateBackupHandler(factory);
  }

  private APIGatewayV2HTTPEvent buildEvent(String user, String body) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withBody(body)
        .build();
  }

  @Test
  void handleRequestShouldReturnSkippedWhenRecentCompletedBackupExists() throws Exception {
    // arrange
    var user = "alice";
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    var existingItem = new AnkiBackupItem();
    existingItem.setPk(AnkiBackupItem.formatPk(user));
    existingItem.setSk(AnkiBackupItem.formatSk("existing-backup-id"));
    existingItem.setBackupId("existing-backup-id");
    existingItem.setStatus(AnkiBackupItem.STATUS_COMPLETED);
    existingItem.setProfileId("japanese-main");
    existingItem.setS3Bucket(CreateBackupHandler.BUCKET);
    existingItem.setS3Key("users/alice/profiles/japanese-main/backups/2026/03/01/existing.colpkg");
    existingItem.setUploadId("upload-123");
    existingItem.setPartSizeBytes(CreateBackupHandler.PART_SIZE_BYTES);
    existingItem.setSizeBytes(534773760L);
    existingItem.setSha256("abc123");
    existingItem.setCreatedAt(now.minus(Duration.ofHours(2)));
    existingItem.setCompletedAt(now.minus(Duration.ofHours(1)));
    existingItem.setExpiresAt(now.plus(Duration.ofDays(90)));
    existingItem.setTtl(now.plus(Duration.ofDays(90)).getEpochSecond());
    ankiBackupTable.putItem(existingItem);

    var body =
        objectMapper.writeValueAsString(
            new CreateBackupHandler.CreateBackupRequest(
                "japanese-main",
                new CreateBackupHandler.Artifact("collection.colpkg", 1024L, "sha256hash")));
    var event = buildEvent(user, body);

    // act
    var res = createBackupHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    var tree = objectMapper.readTree(res.getBody());
    assertThat(tree.get("status").asText()).isEqualTo("skipped");
    assertThat(tree.size()).isEqualTo(1);
  }

  @Test
  void handleRequestShouldReturnReadyWhenCompletedBackupIsOutsideInterval() throws Exception {
    // arrange
    var user = "alice";
    var now = Instant.parse("2026-03-02T12:00:00Z");
    fakeClock.setTime(now);

    var oldItem = new AnkiBackupItem();
    oldItem.setPk(AnkiBackupItem.formatPk(user));
    oldItem.setSk(AnkiBackupItem.formatSk("old-backup-id"));
    oldItem.setBackupId("old-backup-id");
    oldItem.setStatus(AnkiBackupItem.STATUS_COMPLETED);
    oldItem.setProfileId("japanese-main");
    oldItem.setS3Bucket(CreateBackupHandler.BUCKET);
    oldItem.setS3Key("users/alice/profiles/japanese-main/backups/2026/03/01/old.colpkg");
    oldItem.setUploadId("upload-old");
    oldItem.setPartSizeBytes(CreateBackupHandler.PART_SIZE_BYTES);
    oldItem.setSizeBytes(1024L);
    oldItem.setSha256("sha256old");
    oldItem.setCreatedAt(now.minus(Duration.ofHours(30)));
    oldItem.setCompletedAt(now.minus(Duration.ofHours(25)));
    oldItem.setExpiresAt(now.plus(Duration.ofDays(90)));
    oldItem.setTtl(now.plus(Duration.ofDays(90)).getEpochSecond());
    ankiBackupTable.putItem(oldItem);

    var body =
        objectMapper.writeValueAsString(
            new CreateBackupHandler.CreateBackupRequest(
                "japanese-main",
                new CreateBackupHandler.Artifact("collection.colpkg", 1024L, "sha256new")));
    var event = buildEvent(user, body);

    // act
    var res = createBackupHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(201);
    var tree = objectMapper.readTree(res.getBody());
    assertThat(tree.get("status").asText()).isEqualTo("ready");
    assertThat(tree.get("backup").get("status").asText()).isEqualTo("PENDING");
    assertThat(tree.get("backup").get("profile_id").asText()).isEqualTo("japanese-main");
    assertThat(tree.get("upload").get("parts").size()).isGreaterThan(0);
  }

  @Test
  void handleRequestShouldReturnReadyWhenOnlyPendingBackupsExist() throws Exception {
    // arrange
    var user = "alice";
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    var pendingItem = new AnkiBackupItem();
    pendingItem.setPk(AnkiBackupItem.formatPk(user));
    pendingItem.setSk(AnkiBackupItem.formatSk("pending-backup-id"));
    pendingItem.setBackupId("pending-backup-id");
    pendingItem.setStatus(AnkiBackupItem.STATUS_PENDING);
    pendingItem.setProfileId("japanese-main");
    pendingItem.setS3Bucket(CreateBackupHandler.BUCKET);
    pendingItem.setS3Key("users/alice/profiles/japanese-main/backups/2026/03/01/pending.colpkg");
    pendingItem.setUploadId("upload-pending");
    pendingItem.setPartSizeBytes(CreateBackupHandler.PART_SIZE_BYTES);
    pendingItem.setSizeBytes(1024L);
    pendingItem.setSha256("sha256pending");
    pendingItem.setCreatedAt(now.minus(Duration.ofMinutes(30)));
    pendingItem.setExpiresAt(now.plus(Duration.ofDays(90)));
    pendingItem.setTtl(now.plus(Duration.ofDays(90)).getEpochSecond());
    ankiBackupTable.putItem(pendingItem);

    var body =
        objectMapper.writeValueAsString(
            new CreateBackupHandler.CreateBackupRequest(
                "japanese-main",
                new CreateBackupHandler.Artifact("collection.colpkg", 1024L, "sha256new")));
    var event = buildEvent(user, body);

    // act
    var res = createBackupHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(201);
    var tree = objectMapper.readTree(res.getBody());
    assertThat(tree.get("status").asText()).isEqualTo("ready");
    assertThat(tree.get("backup").get("status").asText()).isEqualTo("PENDING");
    assertThat(tree.get("upload").get("parts").size()).isGreaterThan(0);
  }

  @Test
  void handleRequestShouldReturnSkippedWhenCompletedAtExactlyAtIntervalBoundary() throws Exception {
    // arrange
    var user = "alice";
    var now = Instant.parse("2026-03-02T10:00:00Z");
    fakeClock.setTime(now);

    var boundaryItem = new AnkiBackupItem();
    boundaryItem.setPk(AnkiBackupItem.formatPk(user));
    boundaryItem.setSk(AnkiBackupItem.formatSk("boundary-backup"));
    boundaryItem.setBackupId("boundary-backup");
    boundaryItem.setStatus(AnkiBackupItem.STATUS_COMPLETED);
    boundaryItem.setProfileId("japanese-main");
    boundaryItem.setS3Bucket(CreateBackupHandler.BUCKET);
    boundaryItem.setS3Key("users/alice/profiles/japanese-main/backups/2026/03/01/boundary.colpkg");
    boundaryItem.setUploadId("upload-boundary");
    boundaryItem.setPartSizeBytes(CreateBackupHandler.PART_SIZE_BYTES);
    boundaryItem.setSizeBytes(1024L);
    boundaryItem.setSha256("sha256boundary");
    boundaryItem.setCreatedAt(now.minus(Duration.ofHours(25)));
    boundaryItem.setCompletedAt(now.minus(Duration.ofHours(24)));
    boundaryItem.setExpiresAt(now.plus(Duration.ofDays(90)));
    boundaryItem.setTtl(now.plus(Duration.ofDays(90)).getEpochSecond());
    ankiBackupTable.putItem(boundaryItem);

    var body =
        objectMapper.writeValueAsString(
            new CreateBackupHandler.CreateBackupRequest(
                "japanese-main",
                new CreateBackupHandler.Artifact("collection.colpkg", 1024L, "sha256new")));
    var event = buildEvent(user, body);

    // act
    var res = createBackupHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    var tree = objectMapper.readTree(res.getBody());
    assertThat(tree.get("status").asText()).isEqualTo("skipped");
  }

  @Test
  void handleRequestShouldNotCreateNewItemsWhenSkipped() throws Exception {
    // arrange
    var user = "alice";
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    var existingItem = new AnkiBackupItem();
    existingItem.setPk(AnkiBackupItem.formatPk(user));
    existingItem.setSk(AnkiBackupItem.formatSk("existing-backup"));
    existingItem.setBackupId("existing-backup");
    existingItem.setStatus(AnkiBackupItem.STATUS_COMPLETED);
    existingItem.setProfileId("japanese-main");
    existingItem.setS3Bucket(CreateBackupHandler.BUCKET);
    existingItem.setS3Key("users/alice/profiles/japanese-main/backups/2026/03/01/existing.colpkg");
    existingItem.setUploadId("upload-existing");
    existingItem.setPartSizeBytes(CreateBackupHandler.PART_SIZE_BYTES);
    existingItem.setSizeBytes(1024L);
    existingItem.setSha256("sha256existing");
    existingItem.setCreatedAt(now.minus(Duration.ofHours(2)));
    existingItem.setCompletedAt(now.minus(Duration.ofHours(1)));
    existingItem.setExpiresAt(now.plus(Duration.ofDays(90)));
    existingItem.setTtl(now.plus(Duration.ofDays(90)).getEpochSecond());
    ankiBackupTable.putItem(existingItem);

    var body =
        objectMapper.writeValueAsString(
            new CreateBackupHandler.CreateBackupRequest(
                "japanese-main",
                new CreateBackupHandler.Artifact("collection.colpkg", 1024L, "sha256new")));
    var event = buildEvent(user, body);

    // act
    var res = createBackupHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    var items = ankiBackupTable.scan().items().stream().toList();
    assertThat(items).hasSize(1);
    assertThat(items.get(0).getBackupId()).isEqualTo("existing-backup");
  }

  @Test
  void handleRequestShouldReturnReadyWhenDifferentUserHasRecentBackup() throws Exception {
    // arrange
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    var otherUserItem = new AnkiBackupItem();
    otherUserItem.setPk(AnkiBackupItem.formatPk("bob"));
    otherUserItem.setSk(AnkiBackupItem.formatSk("bob-backup-id"));
    otherUserItem.setBackupId("bob-backup-id");
    otherUserItem.setStatus(AnkiBackupItem.STATUS_COMPLETED);
    otherUserItem.setProfileId("main");
    otherUserItem.setS3Bucket(CreateBackupHandler.BUCKET);
    otherUserItem.setS3Key("users/bob/profiles/main/backups/2026/03/01/bob.colpkg");
    otherUserItem.setUploadId("upload-bob");
    otherUserItem.setPartSizeBytes(CreateBackupHandler.PART_SIZE_BYTES);
    otherUserItem.setSizeBytes(1024L);
    otherUserItem.setSha256("sha256bob");
    otherUserItem.setCreatedAt(now.minus(Duration.ofMinutes(30)));
    otherUserItem.setCompletedAt(now.minus(Duration.ofMinutes(20)));
    otherUserItem.setExpiresAt(now.plus(Duration.ofDays(90)));
    otherUserItem.setTtl(now.plus(Duration.ofDays(90)).getEpochSecond());
    ankiBackupTable.putItem(otherUserItem);

    var body =
        objectMapper.writeValueAsString(
            new CreateBackupHandler.CreateBackupRequest(
                "main",
                new CreateBackupHandler.Artifact("collection.colpkg", 1024L, "sha256alice")));
    var event = buildEvent("alice", body);

    // act
    var res = createBackupHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(201);
    var tree = objectMapper.readTree(res.getBody());
    assertThat(tree.get("status").asText()).isEqualTo("ready");
    assertThat(tree.get("backup").get("profile_id").asText()).isEqualTo("main");
  }

  @Test
  void handleRequestShouldPersistPendingItemAndReturnPresignedUrls() throws Exception {
    // arrange
    var user = "alice";
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    var body =
        objectMapper.writeValueAsString(
            new CreateBackupHandler.CreateBackupRequest(
                "japanese-main",
                new CreateBackupHandler.Artifact("collection.colpkg", 1024L, "sha256hash")));
    var event = buildEvent(user, body);

    // act
    var res = createBackupHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(201);
    var tree = objectMapper.readTree(res.getBody());
    assertThat(tree.get("status").asText()).isEqualTo("ready");

    var backup = tree.get("backup");
    assertThat(backup.get("backup_id").asText()).isNotEmpty();
    assertThat(backup.get("status").asText()).isEqualTo("PENDING");
    assertThat(backup.get("profile_id").asText()).isEqualTo("japanese-main");
    assertThat(backup.get("size_bytes").asLong()).isEqualTo(1024L);
    assertThat(backup.get("sha256").asText()).isEqualTo("sha256hash");
    assertThat(backup.get("created_at").asText()).isEqualTo(now.toString());
    assertThat(backup.get("completed_at").isNull()).isTrue();
    assertThat(backup.get("expires_at").asText())
        .isEqualTo(now.plus(Duration.ofDays(90)).toString());

    var upload = tree.get("upload");
    assertThat(upload.get("part_size_bytes").asLong())
        .isEqualTo(CreateBackupHandler.PART_SIZE_BYTES);
    assertThat(upload.get("parts")).hasSize(1);
    assertThat(upload.get("parts").get(0).get("part_number").asInt()).isEqualTo(1);
    assertThat(upload.get("parts").get(0).get("upload_url").asText()).isNotEmpty();

    // verify DynamoDB item
    var items = ankiBackupTable.scan().items().stream().toList();
    assertThat(items).hasSize(1);
    var item = items.get(0);
    assertThat(item.getBackupId()).isEqualTo(backup.get("backup_id").asText());
    assertThat(item.getStatus()).isEqualTo(AnkiBackupItem.STATUS_PENDING);
    assertThat(item.getUploadId()).isNotNull();
    assertThat(item.getS3Bucket()).isEqualTo(CreateBackupHandler.BUCKET);
    assertThat(item.getS3Key()).contains("users/alice/profiles/japanese-main/backups/");
  }
}
