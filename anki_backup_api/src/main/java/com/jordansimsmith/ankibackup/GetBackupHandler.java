package com.jordansimsmith.ankibackup;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

public class GetBackupHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetBackupHandler.class);

  @VisibleForTesting static final String BUCKET = "anki-backup.jordansimsmith.com";
  @VisibleForTesting static final int DOWNLOAD_URL_TTL_SECONDS = 3600;

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

  public GetBackupHandler() {
    this(AnkiBackupFactory.create());
  }

  @VisibleForTesting
  GetBackupHandler(AnkiBackupFactory factory) {
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
    throw new UnsupportedOperationException("Not yet implemented");
  }
}
