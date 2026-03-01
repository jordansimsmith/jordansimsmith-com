package com.jordansimsmith.ankibackup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AnkiBackupDtoTest {
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
  }

  @Test
  void createBackupRequestShouldDeserializeFromJson() throws Exception {
    // arrange
    var json =
        """
        {
          "profile_id": "japanese-main",
          "artifact": {
            "filename": "collection-2026-03-01.colpkg",
            "size_bytes": 534773760,
            "sha256": "0f7a6f8f64028f5f2f1f5a9a2b745f9028ce8f5df5c9a2c7d61f73b05c5ce12b"
          }
        }
        """;

    // act
    var request = objectMapper.readValue(json, CreateBackupHandler.CreateBackupRequest.class);

    // assert
    assertThat(request.profileId()).isEqualTo("japanese-main");
    assertThat(request.artifact().filename()).isEqualTo("collection-2026-03-01.colpkg");
    assertThat(request.artifact().sizeBytes()).isEqualTo(534773760L);
    assertThat(request.artifact().sha256())
        .isEqualTo("0f7a6f8f64028f5f2f1f5a9a2b745f9028ce8f5df5c9a2c7d61f73b05c5ce12b");
  }

  @Test
  void createBackupReadyResponseShouldSerializeToJson() throws Exception {
    // arrange
    var backup =
        new CreateBackupHandler.BackupResponse(
            "550e8400-e29b-41d4-a716-446655440000",
            "japanese-main",
            AnkiBackupItem.STATUS_PENDING,
            "2026-03-01T10:23:01Z",
            null,
            534773760L,
            "0f7a6f8f64028f5f2f1f5a9a2b745f9028ce8f5df5c9a2c7d61f73b05c5ce12b",
            "2026-05-30T10:23:01Z",
            null,
            null);
    var parts =
        List.of(
            new CreateBackupHandler.UploadPartResponse(1, "https://s3.example.com/part1"),
            new CreateBackupHandler.UploadPartResponse(2, "https://s3.example.com/part2"));
    var upload = new CreateBackupHandler.UploadResponse(67108864L, "2026-03-01T11:23:01Z", parts);
    var response = new CreateBackupHandler.CreateBackupReadyResponse("ready", backup, upload);

    // act
    var json = objectMapper.writeValueAsString(response);
    var tree = objectMapper.readTree(json);

    // assert
    assertThat(tree.get("status").asText()).isEqualTo("ready");

    var backupNode = tree.get("backup");
    assertThat(backupNode.get("backup_id").asText())
        .isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    assertThat(backupNode.get("profile_id").asText()).isEqualTo("japanese-main");
    assertThat(backupNode.get("status").asText()).isEqualTo("PENDING");
    assertThat(backupNode.get("created_at").asText()).isEqualTo("2026-03-01T10:23:01Z");
    assertThat(backupNode.get("completed_at").isNull()).isTrue();
    assertThat(backupNode.get("size_bytes").asLong()).isEqualTo(534773760L);
    assertThat(backupNode.get("sha256").asText())
        .isEqualTo("0f7a6f8f64028f5f2f1f5a9a2b745f9028ce8f5df5c9a2c7d61f73b05c5ce12b");
    assertThat(backupNode.get("expires_at").asText()).isEqualTo("2026-05-30T10:23:01Z");
    assertThat(backupNode.get("download_url").isNull()).isTrue();
    assertThat(backupNode.get("download_url_expires_at").isNull()).isTrue();

    var uploadNode = tree.get("upload");
    assertThat(uploadNode.get("part_size_bytes").asLong()).isEqualTo(67108864L);
    assertThat(uploadNode.get("expires_at").asText()).isEqualTo("2026-03-01T11:23:01Z");
    assertThat(uploadNode.get("parts")).hasSize(2);
    assertThat(uploadNode.get("parts").get(0).get("part_number").asInt()).isEqualTo(1);
    assertThat(uploadNode.get("parts").get(0).get("upload_url").asText())
        .isEqualTo("https://s3.example.com/part1");
  }

  @Test
  void createBackupSkippedResponseShouldSerializeToJson() throws Exception {
    // arrange
    var response = new CreateBackupHandler.CreateBackupSkippedResponse("skipped");

    // act
    var json = objectMapper.writeValueAsString(response);
    var tree = objectMapper.readTree(json);

    // assert
    assertThat(tree.get("status").asText()).isEqualTo("skipped");
    assertThat(tree.size()).isEqualTo(1);
  }

  @Test
  void updateBackupRequestShouldDeserializeFromJson() throws Exception {
    // arrange
    var json =
        """
        {"status": "COMPLETED"}
        """;

    // act
    var request = objectMapper.readValue(json, UpdateBackupHandler.UpdateBackupRequest.class);

    // assert
    assertThat(request.status()).isEqualTo("COMPLETED");
  }

  @Test
  void updateBackupResponseShouldSerializeToJson() throws Exception {
    // arrange
    var response = new UpdateBackupHandler.UpdateBackupResponse("completed");

    // act
    var json = objectMapper.writeValueAsString(response);
    var tree = objectMapper.readTree(json);

    // assert
    assertThat(tree.get("status").asText()).isEqualTo("completed");
    assertThat(tree.size()).isEqualTo(1);
  }

  @Test
  void findBackupsResponseShouldSerializeToJson() throws Exception {
    // arrange
    var backup =
        new FindBackupsHandler.BackupResponse(
            "550e8400-e29b-41d4-a716-446655440000",
            "japanese-main",
            AnkiBackupItem.STATUS_COMPLETED,
            "2026-03-01T10:24:20Z",
            "2026-03-01T10:24:45Z",
            534773760L,
            "0f7a6f8f64028f5f2f1f5a9a2b745f9028ce8f5df5c9a2c7d61f73b05c5ce12b",
            "2026-05-30T10:24:45Z",
            null,
            null);
    var response = new FindBackupsHandler.FindBackupsResponse(List.of(backup));

    // act
    var json = objectMapper.writeValueAsString(response);
    var tree = objectMapper.readTree(json);

    // assert
    assertThat(tree.get("backups")).hasSize(1);
    var backupNode = tree.get("backups").get(0);
    assertThat(backupNode.get("backup_id").asText())
        .isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    assertThat(backupNode.get("status").asText()).isEqualTo("COMPLETED");
    assertThat(backupNode.get("completed_at").asText()).isEqualTo("2026-03-01T10:24:45Z");
    assertThat(backupNode.get("download_url").isNull()).isTrue();
    assertThat(backupNode.get("download_url_expires_at").isNull()).isTrue();
  }

  @Test
  void findBackupsResponseShouldSerializeEmptyList() throws Exception {
    // arrange
    var response = new FindBackupsHandler.FindBackupsResponse(List.of());

    // act
    var json = objectMapper.writeValueAsString(response);
    var tree = objectMapper.readTree(json);

    // assert
    assertThat(tree.get("backups")).hasSize(0);
  }

  @Test
  void getBackupResponseShouldSerializeWithDownloadUrl() throws Exception {
    // arrange
    var backup =
        new GetBackupHandler.BackupResponse(
            "550e8400-e29b-41d4-a716-446655440000",
            "japanese-main",
            AnkiBackupItem.STATUS_COMPLETED,
            "2026-03-01T10:24:20Z",
            "2026-03-01T10:24:45Z",
            534773760L,
            "0f7a6f8f64028f5f2f1f5a9a2b745f9028ce8f5df5c9a2c7d61f73b05c5ce12b",
            "2026-05-30T10:24:45Z",
            "https://s3.example.com/download",
            "2026-03-01T11:00:00Z");
    var response = new GetBackupHandler.GetBackupResponse(backup);

    // act
    var json = objectMapper.writeValueAsString(response);
    var tree = objectMapper.readTree(json);

    // assert
    var backupNode = tree.get("backup");
    assertThat(backupNode.get("backup_id").asText())
        .isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    assertThat(backupNode.get("download_url").asText())
        .isEqualTo("https://s3.example.com/download");
    assertThat(backupNode.get("download_url_expires_at").asText())
        .isEqualTo("2026-03-01T11:00:00Z");
  }

  @Test
  void backupStatusConstantsShouldMatchExpectedValues() {
    assertThat(AnkiBackupItem.STATUS_PENDING).isEqualTo("PENDING");
    assertThat(AnkiBackupItem.STATUS_COMPLETED).isEqualTo("COMPLETED");
  }

  @Test
  void ankiBackupItemShouldFormatKeysCorrectly() {
    assertThat(AnkiBackupItem.formatPk("alice")).isEqualTo("USER#alice");
    assertThat(AnkiBackupItem.formatSk("550e8400-e29b-41d4-a716-446655440000"))
        .isEqualTo("BACKUP#550e8400-e29b-41d4-a716-446655440000");
  }

  @Test
  void createBackupRequestShouldRoundTripThroughJson() throws Exception {
    // arrange
    var original =
        new CreateBackupHandler.CreateBackupRequest(
            "main", new CreateBackupHandler.Artifact("collection.colpkg", 1024L, "abc123"));

    // act
    var json = objectMapper.writeValueAsString(original);
    var deserialized = objectMapper.readValue(json, CreateBackupHandler.CreateBackupRequest.class);

    // assert
    assertThat(deserialized.profileId()).isEqualTo("main");
    assertThat(deserialized.artifact().filename()).isEqualTo("collection.colpkg");
    assertThat(deserialized.artifact().sizeBytes()).isEqualTo(1024L);
    assertThat(deserialized.artifact().sha256()).isEqualTo("abc123");
  }

  @Test
  void updateBackupErrorResponseShouldSerializeToJson() throws Exception {
    // arrange
    var response = new UpdateBackupHandler.ErrorResponse("backup not found");

    // act
    var json = objectMapper.writeValueAsString(response);
    var tree = objectMapper.readTree(json);

    // assert
    assertThat(tree.get("message").asText()).isEqualTo("backup not found");
    assertThat(tree.size()).isEqualTo(1);
  }

  @Test
  void getBackupErrorResponseShouldSerializeToJson() throws Exception {
    // arrange
    var response = new GetBackupHandler.ErrorResponse("backup not completed");

    // act
    var json = objectMapper.writeValueAsString(response);
    var tree = objectMapper.readTree(json);

    // assert
    assertThat(tree.get("message").asText()).isEqualTo("backup not completed");
    assertThat(tree.size()).isEqualTo(1);
  }
}
