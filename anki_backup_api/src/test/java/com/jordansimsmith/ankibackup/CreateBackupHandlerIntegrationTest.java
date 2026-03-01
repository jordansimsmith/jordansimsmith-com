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

    var existingItem =
        AnkiBackupItem.create(
            user,
            "existing-backup-id",
            "japanese-main",
            CreateBackupHandler.BUCKET,
            "users/alice/profiles/japanese-main/backups/2026/03/01/existing.colpkg",
            "upload-123",
            CreateBackupHandler.PART_SIZE_BYTES,
            534773760L,
            "abc123",
            now.minus(Duration.ofHours(2)),
            now.plus(Duration.ofDays(90)));
    existingItem.setStatus(AnkiBackupItem.STATUS_COMPLETED);
    existingItem.setCompletedAt(now.minus(Duration.ofHours(1)));
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

    var oldItem =
        AnkiBackupItem.create(
            user,
            "old-backup-id",
            "japanese-main",
            CreateBackupHandler.BUCKET,
            "users/alice/profiles/japanese-main/backups/2026/03/01/old.colpkg",
            "upload-old",
            CreateBackupHandler.PART_SIZE_BYTES,
            1024L,
            "sha256old",
            now.minus(Duration.ofHours(30)),
            now.plus(Duration.ofDays(90)));
    oldItem.setStatus(AnkiBackupItem.STATUS_COMPLETED);
    oldItem.setCompletedAt(now.minus(Duration.ofHours(25)));
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

    var pendingItem =
        AnkiBackupItem.create(
            user,
            "pending-backup-id",
            "japanese-main",
            CreateBackupHandler.BUCKET,
            "users/alice/profiles/japanese-main/backups/2026/03/01/pending.colpkg",
            "upload-pending",
            CreateBackupHandler.PART_SIZE_BYTES,
            1024L,
            "sha256pending",
            now.minus(Duration.ofMinutes(30)),
            now.plus(Duration.ofDays(90)));
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

    var boundaryItem =
        AnkiBackupItem.create(
            user,
            "boundary-backup",
            "japanese-main",
            CreateBackupHandler.BUCKET,
            "users/alice/profiles/japanese-main/backups/2026/03/01/boundary.colpkg",
            "upload-boundary",
            CreateBackupHandler.PART_SIZE_BYTES,
            1024L,
            "sha256boundary",
            now.minus(Duration.ofHours(25)),
            now.plus(Duration.ofDays(90)));
    boundaryItem.setStatus(AnkiBackupItem.STATUS_COMPLETED);
    boundaryItem.setCompletedAt(now.minus(Duration.ofHours(24)));
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

    var existingItem =
        AnkiBackupItem.create(
            user,
            "existing-backup",
            "japanese-main",
            CreateBackupHandler.BUCKET,
            "users/alice/profiles/japanese-main/backups/2026/03/01/existing.colpkg",
            "upload-existing",
            CreateBackupHandler.PART_SIZE_BYTES,
            1024L,
            "sha256existing",
            now.minus(Duration.ofHours(2)),
            now.plus(Duration.ofDays(90)));
    existingItem.setStatus(AnkiBackupItem.STATUS_COMPLETED);
    existingItem.setCompletedAt(now.minus(Duration.ofHours(1)));
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

    var otherUserItem =
        AnkiBackupItem.create(
            "bob",
            "bob-backup-id",
            "main",
            CreateBackupHandler.BUCKET,
            "users/bob/profiles/main/backups/2026/03/01/bob.colpkg",
            "upload-bob",
            CreateBackupHandler.PART_SIZE_BYTES,
            1024L,
            "sha256bob",
            now.minus(Duration.ofMinutes(30)),
            now.plus(Duration.ofDays(90)));
    otherUserItem.setStatus(AnkiBackupItem.STATUS_COMPLETED);
    otherUserItem.setCompletedAt(now.minus(Duration.ofMinutes(20)));
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
  void handleRequestShouldReturnEightPartsWhenFiveHundredMegabyteArtifact() throws Exception {
    // arrange
    var user = "alice";
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    var body =
        objectMapper.writeValueAsString(
            new CreateBackupHandler.CreateBackupRequest(
                "japanese-main",
                new CreateBackupHandler.Artifact("collection.colpkg", 534_773_760L, "sha256hash")));
    var event = buildEvent(user, body);

    // act
    var res = createBackupHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(201);
    var tree = objectMapper.readTree(res.getBody());
    assertThat(tree.get("status").asText()).isEqualTo("ready");
    assertThat(tree.get("upload").get("parts")).hasSize(8);
    assertThat(tree.get("upload").get("parts").get(0).get("part_number").asInt()).isEqualTo(1);
    assertThat(tree.get("upload").get("parts").get(7).get("part_number").asInt()).isEqualTo(8);
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
