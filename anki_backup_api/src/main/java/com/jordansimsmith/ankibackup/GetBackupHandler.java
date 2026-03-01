package com.jordansimsmith.ankibackup;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.time.Clock;
import java.time.Duration;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

public class GetBackupHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetBackupHandler.class);

  @VisibleForTesting static final String BUCKET = "anki-backup.jordansimsmith.com";
  @VisibleForTesting static final int DOWNLOAD_URL_TTL_SECONDS = 3600;

  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<AnkiBackupItem> ankiBackupTable;
  private final S3Presigner s3Presigner;

  @VisibleForTesting
  record GetBackupResponse(@JsonProperty("backup") BackupResponse backup) {}

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
  record ErrorResponse(@JsonProperty("message") String message) {}

  public GetBackupHandler() {
    this(AnkiBackupFactory.create());
  }

  @VisibleForTesting
  GetBackupHandler(AnkiBackupFactory factory) {
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.ankiBackupTable = factory.ankiBackupTable();
    this.s3Presigner = factory.s3Presigner();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("Error processing get backup request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var user = requestContextFactory.createCtx(event).user();
    var backupId = event.getPathParameters().get("backup_id");

    var key =
        Key.builder()
            .partitionValue(AnkiBackupItem.formatPk(user))
            .sortValue(AnkiBackupItem.formatSk(backupId))
            .build();
    var item = ankiBackupTable.getItem(key);

    if (item == null) {
      return httpResponseFactory.notFound(new ErrorResponse("backup not found"));
    }

    if (!AnkiBackupItem.STATUS_COMPLETED.equals(item.getStatus())) {
      return httpResponseFactory.badRequest(new ErrorResponse("backup not completed"));
    }

    var downloadUrlDuration = Duration.ofSeconds(DOWNLOAD_URL_TTL_SECONDS);
    var presigned =
        s3Presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(downloadUrlDuration)
                .getObjectRequest(
                    GetObjectRequest.builder()
                        .bucket(item.getS3Bucket())
                        .key(item.getS3Key())
                        .build())
                .build());

    var downloadUrlExpiresAt = clock.now().plus(downloadUrlDuration);

    var backupResponse =
        new BackupResponse(
            item.getBackupId(),
            item.getProfileId(),
            item.getStatus(),
            item.getCreatedAt().toString(),
            item.getCompletedAt() != null ? item.getCompletedAt().toString() : null,
            item.getSizeBytes(),
            item.getSha256(),
            item.getExpiresAt().toString(),
            presigned.url().toString(),
            downloadUrlExpiresAt.toString());

    return httpResponseFactory.ok(new GetBackupResponse(backupResponse));
  }
}
