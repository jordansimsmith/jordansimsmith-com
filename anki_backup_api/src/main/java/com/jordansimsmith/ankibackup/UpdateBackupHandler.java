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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;

public class UpdateBackupHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateBackupHandler.class);

  @VisibleForTesting static final String BUCKET = "anki-backup.jordansimsmith.com";

  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<AnkiBackupItem> ankiBackupTable;
  private final S3Client s3Client;

  @VisibleForTesting
  record UpdateBackupRequest(@JsonProperty("status") String status) {}

  @VisibleForTesting
  record UpdateBackupResponse(@JsonProperty("status") String status) {}

  @VisibleForTesting
  record ErrorResponse(@JsonProperty("message") String message) {}

  public UpdateBackupHandler() {
    this(AnkiBackupFactory.create());
  }

  @VisibleForTesting
  UpdateBackupHandler(AnkiBackupFactory factory) {
    this.clock = factory.clock();
    this.objectMapper = factory.objectMapper();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.ankiBackupTable = factory.ankiBackupTable();
    this.s3Client = factory.s3Client();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("Error processing update backup request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var user = requestContextFactory.createCtx(event).user();
    var backupId = event.getPathParameters().get("backup_id");
    var body = objectMapper.readValue(event.getBody(), UpdateBackupRequest.class);

    if (!AnkiBackupItem.STATUS_COMPLETED.equals(body.status())) {
      return httpResponseFactory.badRequest(
          new ErrorResponse("status must be " + AnkiBackupItem.STATUS_COMPLETED));
    }

    var key =
        Key.builder()
            .partitionValue(AnkiBackupItem.formatPk(user))
            .sortValue(AnkiBackupItem.formatSk(backupId))
            .build();
    var item = ankiBackupTable.getItem(key);

    if (item == null) {
      return httpResponseFactory.notFound(new ErrorResponse("backup not found"));
    }

    if (AnkiBackupItem.STATUS_COMPLETED.equals(item.getStatus())) {
      return httpResponseFactory.badRequest(new ErrorResponse("backup already completed"));
    }

    var listPartsResponse =
        s3Client.listParts(
            ListPartsRequest.builder()
                .bucket(item.getS3Bucket())
                .key(item.getS3Key())
                .uploadId(item.getUploadId())
                .build());

    var completedParts =
        listPartsResponse.parts().stream()
            .map(
                part ->
                    CompletedPart.builder().partNumber(part.partNumber()).eTag(part.eTag()).build())
            .toList();

    s3Client.completeMultipartUpload(
        CompleteMultipartUploadRequest.builder()
            .bucket(item.getS3Bucket())
            .key(item.getS3Key())
            .uploadId(item.getUploadId())
            .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
            .build());

    var now = clock.now();
    item.setStatus(AnkiBackupItem.STATUS_COMPLETED);
    item.setCompletedAt(now);
    ankiBackupTable.updateItem(item);

    return httpResponseFactory.ok(new UpdateBackupResponse("completed"));
  }
}
