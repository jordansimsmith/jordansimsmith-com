package com.jordansimsmith.ankibackup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

@Testcontainers
public class AnkiBackupE2ETest {

  @Container private static final AnkiBackupContainer container = new AnkiBackupContainer();

  private HttpClient httpClient;
  private ObjectMapper objectMapper;
  private URI apiUrl;
  private DynamoDbClient dynamoDbClient;
  private S3Client s3Client;

  @BeforeEach
  void setup() {
    dynamoDbClient =
        DynamoDbClient.builder().endpointOverride(container.getLocalstackUrl()).build();
    DynamoDbUtils.reset(dynamoDbClient);

    s3Client =
        S3Client.builder()
            .endpointOverride(container.getLocalstackUrl())
            .forcePathStyle(true)
            .build();

    httpClient = HttpClient.newHttpClient();
    objectMapper = new ObjectMapper();
    apiUrl = container.getApiUrl();
  }

  @Test
  void shouldCreateUploadCompleteListAndGetBackup() throws Exception {
    var authHeader =
        "Basic "
            + Base64.getEncoder().encodeToString("alice:password".getBytes(StandardCharsets.UTF_8));
    var partData = "test-backup-content-for-e2e".getBytes(StandardCharsets.UTF_8);

    // create backup
    var createBody =
        objectMapper.writeValueAsString(
            new CreateBackupHandler.CreateBackupRequest(
                "main",
                new CreateBackupHandler.Artifact(
                    "collection.colpkg", partData.length, "test-sha256")));

    var createHttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/backups"))
            .header("Content-Type", "application/json")
            .header("Authorization", authHeader)
            .POST(HttpRequest.BodyPublishers.ofString(createBody))
            .build();

    var createHttpResponse =
        httpClient.send(createHttpRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(createHttpResponse.statusCode()).isEqualTo(201);

    var createResponse =
        objectMapper.readValue(
            createHttpResponse.body(), CreateBackupHandler.CreateBackupReadyResponse.class);
    assertThat(createResponse.status()).isEqualTo("ready");
    assertThat(createResponse.backup().status()).isEqualTo("PENDING");
    assertThat(createResponse.backup().profileId()).isEqualTo("main");
    assertThat(createResponse.backup().sizeBytes()).isEqualTo(partData.length);
    assertThat(createResponse.backup().sha256()).isEqualTo("test-sha256");
    assertThat(createResponse.backup().completedAt()).isNull();
    assertThat(createResponse.backup().downloadUrl()).isNull();
    assertThat(createResponse.upload().partSizeBytes()).isEqualTo(67_108_864L);
    assertThat(createResponse.upload().parts()).hasSize(1);

    var backupId = createResponse.backup().backupId();

    // upload part directly to S3 using metadata from DynamoDB
    var item =
        dynamoDbClient
            .getItem(
                r ->
                    r.tableName("anki_backup")
                        .key(
                            Map.of(
                                "pk", AttributeValue.builder().s("USER#alice").build(),
                                "sk", AttributeValue.builder().s("BACKUP#" + backupId).build())))
            .item();

    var bucket = item.get("s3_bucket").s();
    var s3Key = item.get("s3_key").s();
    var uploadId = item.get("upload_id").s();

    s3Client.uploadPart(
        UploadPartRequest.builder()
            .bucket(bucket)
            .key(s3Key)
            .uploadId(uploadId)
            .partNumber(1)
            .build(),
        RequestBody.fromBytes(partData));

    // complete backup
    var updateBody =
        objectMapper.writeValueAsString(new UpdateBackupHandler.UpdateBackupRequest("COMPLETED"));

    var updateHttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/backups/" + backupId))
            .header("Content-Type", "application/json")
            .header("Authorization", authHeader)
            .PUT(HttpRequest.BodyPublishers.ofString(updateBody))
            .build();

    var updateHttpResponse =
        httpClient.send(updateHttpRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(updateHttpResponse.statusCode()).isEqualTo(200);

    var updateResponse =
        objectMapper.readValue(
            updateHttpResponse.body(), UpdateBackupHandler.UpdateBackupResponse.class);
    assertThat(updateResponse.status()).isEqualTo("completed");

    // list backups
    var listHttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/backups"))
            .header("Authorization", authHeader)
            .GET()
            .build();

    var listHttpResponse = httpClient.send(listHttpRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(listHttpResponse.statusCode()).isEqualTo(200);

    var listResponse =
        objectMapper.readValue(
            listHttpResponse.body(), FindBackupsHandler.FindBackupsResponse.class);
    assertThat(listResponse.backups()).hasSize(1);
    assertThat(listResponse.backups().get(0).backupId()).isEqualTo(backupId);
    assertThat(listResponse.backups().get(0).status()).isEqualTo("COMPLETED");
    assertThat(listResponse.backups().get(0).profileId()).isEqualTo("main");
    assertThat(listResponse.backups().get(0).completedAt()).isNotNull();
    assertThat(listResponse.backups().get(0).downloadUrl()).isNull();

    // get backup with download URL
    var getHttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/backups/" + backupId))
            .header("Authorization", authHeader)
            .GET()
            .build();

    var getHttpResponse = httpClient.send(getHttpRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(getHttpResponse.statusCode()).isEqualTo(200);

    var getResponse =
        objectMapper.readValue(getHttpResponse.body(), GetBackupHandler.GetBackupResponse.class);
    assertThat(getResponse.backup().backupId()).isEqualTo(backupId);
    assertThat(getResponse.backup().status()).isEqualTo("COMPLETED");
    assertThat(getResponse.backup().profileId()).isEqualTo("main");
    assertThat(getResponse.backup().completedAt()).isNotNull();
    assertThat(getResponse.backup().downloadUrl()).isNotNull();
    assertThat(getResponse.backup().downloadUrlExpiresAt()).isNotNull();

    // verify S3 object content matches uploaded data
    try (var s3Response =
        s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(s3Key).build())) {
      assertThat(s3Response.readAllBytes()).isEqualTo(partData);
    }

    // verify subsequent backup attempt is skipped (completed backup within interval)
    var skipHttpResponse = httpClient.send(createHttpRequest, HttpResponse.BodyHandlers.ofString());
    assertThat(skipHttpResponse.statusCode()).isEqualTo(200);

    var skipResponse =
        objectMapper.readValue(
            skipHttpResponse.body(), CreateBackupHandler.CreateBackupSkippedResponse.class);
    assertThat(skipResponse.status()).isEqualTo("skipped");
  }
}
