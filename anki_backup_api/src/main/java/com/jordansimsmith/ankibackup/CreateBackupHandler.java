package com.jordansimsmith.ankibackup;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

public class CreateBackupHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(CreateBackupHandler.class);

  @VisibleForTesting static final String BUCKET = "anki-backup.jordansimsmith.com";
  @VisibleForTesting static final long PART_SIZE_BYTES = 67_108_864L;
  @VisibleForTesting static final int BACKUP_INTERVAL_HOURS = 24;
  @VisibleForTesting static final int RETENTION_DAYS = 90;
  @VisibleForTesting static final int UPLOAD_URL_TTL_SECONDS = 3600;

  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<AnkiBackupItem> ankiBackupTable;
  private final S3Client s3Client;
  private final S3Presigner s3Presigner;

  @VisibleForTesting
  record CreateBackupRequest(
      @JsonProperty("profile_id") String profileId, @JsonProperty("artifact") Artifact artifact) {}

  @VisibleForTesting
  record Artifact(
      @JsonProperty("filename") String filename,
      @JsonProperty("size_bytes") long sizeBytes,
      @JsonProperty("sha256") String sha256) {}

  @VisibleForTesting
  record CreateBackupReadyResponse(
      @JsonProperty("status") String status,
      @JsonProperty("backup") BackupResponse backup,
      @JsonProperty("upload") UploadResponse upload) {}

  @VisibleForTesting
  record CreateBackupSkippedResponse(@JsonProperty("status") String status) {}

  @VisibleForTesting
  record BackupResponse(
      @JsonProperty("backup_id") String backupId,
      @JsonProperty("profile_id") String profileId,
      @JsonProperty("status") String status,
      @JsonProperty("created_at") String createdAt,
      @Nullable @JsonProperty("completed_at") String completedAt,
      @JsonProperty("size_bytes") long sizeBytes,
      @JsonProperty("sha256") String sha256,
      @JsonProperty("expires_at") String expiresAt,
      @Nullable @JsonProperty("download_url") String downloadUrl,
      @Nullable @JsonProperty("download_url_expires_at") String downloadUrlExpiresAt) {}

  @VisibleForTesting
  record UploadResponse(
      @JsonProperty("part_size_bytes") long partSizeBytes,
      @JsonProperty("expires_at") String expiresAt,
      @JsonProperty("parts") List<UploadPartResponse> parts) {}

  @VisibleForTesting
  record UploadPartResponse(
      @JsonProperty("part_number") int partNumber, @JsonProperty("upload_url") String uploadUrl) {}

  public CreateBackupHandler() {
    this(AnkiBackupFactory.create());
  }

  @VisibleForTesting
  CreateBackupHandler(AnkiBackupFactory factory) {
    this.clock = factory.clock();
    this.objectMapper = factory.objectMapper();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.ankiBackupTable = factory.ankiBackupTable();
    this.s3Client = factory.s3Client();
    this.s3Presigner = factory.s3Presigner();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("Error processing create backup request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var user = requestContextFactory.createCtx(event).user();
    var body = objectMapper.readValue(event.getBody(), CreateBackupRequest.class);

    var now = clock.now();
    var intervalStart = now.minus(Duration.ofHours(BACKUP_INTERVAL_HOURS));

    var recentCompleted =
        ankiBackupTable
            .query(
                QueryEnhancedRequest.builder()
                    .queryConditional(
                        QueryConditional.sortBeginsWith(
                            Key.builder()
                                .partitionValue(AnkiBackupItem.formatPk(user))
                                .sortValue(AnkiBackupItem.BACKUP_PREFIX)
                                .build()))
                    .filterExpression(
                        Expression.builder()
                            .expression("#s = :completed AND #ca >= :intervalStart")
                            .expressionNames(
                                Map.of(
                                    "#s", AnkiBackupItem.STATUS,
                                    "#ca", AnkiBackupItem.COMPLETED_AT))
                            .expressionValues(
                                Map.of(
                                    ":completed",
                                        AttributeValue.builder()
                                            .s(AnkiBackupItem.STATUS_COMPLETED)
                                            .build(),
                                    ":intervalStart",
                                        AttributeValue.builder()
                                            .n(String.valueOf(intervalStart.getEpochSecond()))
                                            .build()))
                            .build())
                    .build())
            .items()
            .stream()
            .findFirst();

    if (recentCompleted.isPresent()) {
      return httpResponseFactory.ok(new CreateBackupSkippedResponse("skipped"));
    }

    var backupId = UUID.randomUUID().toString();
    var createdAt = now;
    var expiresAt = createdAt.plus(Duration.ofDays(RETENTION_DAYS));
    var ttl = expiresAt.getEpochSecond();

    var datePrefix =
        DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC).format(createdAt);
    var s3Key =
        "users/%s/profiles/%s/backups/%s/%s.colpkg"
            .formatted(user, body.profileId(), datePrefix, backupId);

    var multipartUpload =
        s3Client.createMultipartUpload(
            CreateMultipartUploadRequest.builder().bucket(BUCKET).key(s3Key).build());
    var uploadId = multipartUpload.uploadId();

    var item = new AnkiBackupItem();
    item.setPk(AnkiBackupItem.formatPk(user));
    item.setSk(AnkiBackupItem.formatSk(backupId));
    item.setBackupId(backupId);
    item.setStatus(AnkiBackupItem.STATUS_PENDING);
    item.setProfileId(body.profileId());
    item.setS3Bucket(BUCKET);
    item.setS3Key(s3Key);
    item.setUploadId(uploadId);
    item.setPartSizeBytes(PART_SIZE_BYTES);
    item.setSizeBytes(body.artifact().sizeBytes());
    item.setSha256(body.artifact().sha256());
    item.setCreatedAt(createdAt);
    item.setExpiresAt(expiresAt);
    item.setTtl(ttl);
    ankiBackupTable.putItem(item);

    var totalParts = (int) Math.ceil((double) body.artifact().sizeBytes() / PART_SIZE_BYTES);
    if (totalParts == 0) {
      totalParts = 1;
    }

    var uploadUrlExpiry = Duration.ofSeconds(UPLOAD_URL_TTL_SECONDS);
    var parts = new ArrayList<UploadPartResponse>();
    for (int i = 1; i <= totalParts; i++) {
      PresignedUploadPartRequest presigned =
          s3Presigner.presignUploadPart(
              UploadPartPresignRequest.builder()
                  .signatureDuration(uploadUrlExpiry)
                  .uploadPartRequest(
                      UploadPartRequest.builder()
                          .bucket(BUCKET)
                          .key(s3Key)
                          .uploadId(uploadId)
                          .partNumber(i)
                          .build())
                  .build());
      parts.add(new UploadPartResponse(i, presigned.url().toString()));
    }

    var uploadExpiresAt = now.plus(uploadUrlExpiry);
    var backupResponse =
        new BackupResponse(
            backupId,
            body.profileId(),
            AnkiBackupItem.STATUS_PENDING,
            createdAt.toString(),
            null,
            body.artifact().sizeBytes(),
            body.artifact().sha256(),
            expiresAt.toString(),
            null,
            null);
    var uploadResponse = new UploadResponse(PART_SIZE_BYTES, uploadExpiresAt.toString(), parts);
    var response = new CreateBackupReadyResponse("ready", backupResponse, uploadResponse);

    return httpResponseFactory.created(response);
  }
}
