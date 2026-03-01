package com.jordansimsmith.ankibackup;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

public class FindBackupsHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(FindBackupsHandler.class);

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<AnkiBackupItem> ankiBackupTable;

  @VisibleForTesting
  record FindBackupsResponse(@JsonProperty("backups") List<BackupResponse> backups) {}

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

  public FindBackupsHandler() {
    this(AnkiBackupFactory.create());
  }

  @VisibleForTesting
  FindBackupsHandler(AnkiBackupFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.ankiBackupTable = factory.ankiBackupTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("Error processing find backups request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    throw new UnsupportedOperationException("Not yet implemented");
  }
}
