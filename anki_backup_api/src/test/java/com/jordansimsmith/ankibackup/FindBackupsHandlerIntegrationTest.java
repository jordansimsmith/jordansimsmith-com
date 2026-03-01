package com.jordansimsmith.ankibackup;

import static org.assertj.core.api.Assertions.assertThat;

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

@Testcontainers
public class FindBackupsHandlerIntegrationTest {
  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<AnkiBackupItem> ankiBackupTable;

  private FindBackupsHandler findBackupsHandler;

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

    findBackupsHandler = new FindBackupsHandler(factory);
  }

  private APIGatewayV2HTTPEvent buildEvent(String user) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder().withHeaders(Map.of("Authorization", authHeader)).build();
  }

  private AnkiBackupItem createCompletedBackup(
      String user, String backupId, Instant createdAt, Instant completedAt) {
    var item = new AnkiBackupItem();
    item.setPk(AnkiBackupItem.formatPk(user));
    item.setSk(AnkiBackupItem.formatSk(backupId));
    item.setBackupId(backupId);
    item.setStatus(AnkiBackupItem.STATUS_COMPLETED);
    item.setProfileId("japanese-main");
    item.setS3Bucket("anki-backup.jordansimsmith.com");
    item.setS3Key(
        "users/" + user + "/profiles/japanese-main/backups/2026/03/01/" + backupId + ".colpkg");
    item.setUploadId("upload-" + backupId);
    item.setPartSizeBytes(67_108_864L);
    item.setSizeBytes(534773760L);
    item.setSha256("sha256-" + backupId);
    item.setCreatedAt(createdAt);
    item.setCompletedAt(completedAt);
    item.setExpiresAt(createdAt.plus(Duration.ofDays(90)).toString());
    item.setTtl(createdAt.plus(Duration.ofDays(90)).getEpochSecond());
    return item;
  }

  @Test
  void handleRequestShouldReturnEmptyListWhenNoBackupsExist() throws Exception {
    // arrange
    var event = buildEvent("alice");

    // act
    var res = findBackupsHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    var tree = objectMapper.readTree(res.getBody());
    assertThat(tree.get("backups").size()).isEqualTo(0);
  }

  @Test
  void handleRequestShouldReturnSingleCompletedBackup() throws Exception {
    // arrange
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    var item =
        createCompletedBackup(
            "alice", "backup-1", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)));
    ankiBackupTable.putItem(item);

    var event = buildEvent("alice");

    // act
    var res = findBackupsHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    var tree = objectMapper.readTree(res.getBody());
    var backups = tree.get("backups");
    assertThat(backups.size()).isEqualTo(1);

    var backup = backups.get(0);
    assertThat(backup.get("backup_id").asText()).isEqualTo("backup-1");
    assertThat(backup.get("profile_id").asText()).isEqualTo("japanese-main");
    assertThat(backup.get("status").asText()).isEqualTo("COMPLETED");
    assertThat(backup.get("size_bytes").asLong()).isEqualTo(534773760L);
    assertThat(backup.get("sha256").asText()).isEqualTo("sha256-backup-1");
    assertThat(backup.get("completed_at").isNull()).isFalse();
    assertThat(backup.get("download_url").isNull()).isTrue();
    assertThat(backup.get("download_url_expires_at").isNull()).isTrue();
  }

  @Test
  void handleRequestShouldReturnMultipleBackupsSortedByCreatedAtDescending() throws Exception {
    // arrange
    var now = Instant.parse("2026-03-03T10:00:00Z");
    fakeClock.setTime(now);

    var oldest =
        createCompletedBackup(
            "alice",
            "backup-oldest",
            now.minus(Duration.ofDays(3)),
            now.minus(Duration.ofDays(3)).plus(Duration.ofMinutes(5)));
    var middle =
        createCompletedBackup(
            "alice",
            "backup-middle",
            now.minus(Duration.ofDays(1)),
            now.minus(Duration.ofDays(1)).plus(Duration.ofMinutes(5)));
    var newest =
        createCompletedBackup(
            "alice",
            "backup-newest",
            now.minus(Duration.ofHours(2)),
            now.minus(Duration.ofHours(1)));

    ankiBackupTable.putItem(oldest);
    ankiBackupTable.putItem(middle);
    ankiBackupTable.putItem(newest);

    var event = buildEvent("alice");

    // act
    var res = findBackupsHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    var tree = objectMapper.readTree(res.getBody());
    var backups = tree.get("backups");
    assertThat(backups.size()).isEqualTo(3);
    assertThat(backups.get(0).get("backup_id").asText()).isEqualTo("backup-newest");
    assertThat(backups.get(1).get("backup_id").asText()).isEqualTo("backup-middle");
    assertThat(backups.get(2).get("backup_id").asText()).isEqualTo("backup-oldest");
  }

  @Test
  void handleRequestShouldExcludePendingBackups() throws Exception {
    // arrange
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    var completed =
        createCompletedBackup(
            "alice", "completed-1", now.minus(Duration.ofHours(5)), now.minus(Duration.ofHours(4)));
    ankiBackupTable.putItem(completed);

    var pending = new AnkiBackupItem();
    pending.setPk(AnkiBackupItem.formatPk("alice"));
    pending.setSk(AnkiBackupItem.formatSk("pending-1"));
    pending.setBackupId("pending-1");
    pending.setStatus(AnkiBackupItem.STATUS_PENDING);
    pending.setProfileId("japanese-main");
    pending.setS3Bucket("anki-backup.jordansimsmith.com");
    pending.setS3Key("users/alice/profiles/japanese-main/backups/2026/03/01/pending-1.colpkg");
    pending.setUploadId("upload-pending");
    pending.setPartSizeBytes(67_108_864L);
    pending.setSizeBytes(1024L);
    pending.setSha256("sha256-pending");
    pending.setCreatedAt(now.minus(Duration.ofMinutes(10)));
    pending.setExpiresAt(now.plus(Duration.ofDays(90)).toString());
    pending.setTtl(now.plus(Duration.ofDays(90)).getEpochSecond());
    ankiBackupTable.putItem(pending);

    var event = buildEvent("alice");

    // act
    var res = findBackupsHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    var tree = objectMapper.readTree(res.getBody());
    var backups = tree.get("backups");
    assertThat(backups.size()).isEqualTo(1);
    assertThat(backups.get(0).get("backup_id").asText()).isEqualTo("completed-1");
  }

  @Test
  void handleRequestShouldOnlyReturnBackupsForAuthenticatedUser() throws Exception {
    // arrange
    var now = Instant.parse("2026-03-01T10:00:00Z");
    fakeClock.setTime(now);

    var aliceBackup =
        createCompletedBackup(
            "alice",
            "alice-backup",
            now.minus(Duration.ofHours(2)),
            now.minus(Duration.ofHours(1)));
    var bobBackup =
        createCompletedBackup(
            "bob", "bob-backup", now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(2)));

    ankiBackupTable.putItem(aliceBackup);
    ankiBackupTable.putItem(bobBackup);

    var event = buildEvent("alice");

    // act
    var res = findBackupsHandler.handleRequest(event, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    var tree = objectMapper.readTree(res.getBody());
    var backups = tree.get("backups");
    assertThat(backups.size()).isEqualTo(1);
    assertThat(backups.get(0).get("backup_id").asText()).isEqualTo("alice-backup");
  }
}
