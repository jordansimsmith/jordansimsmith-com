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
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

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
    throw new UnsupportedOperationException("Not yet implemented");
  }
}
