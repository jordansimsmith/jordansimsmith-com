package com.jordansimsmith.tcginventory;

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
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class GetPublishHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetPublishHandler.class);

  record PublishResponse(
      @JsonProperty("job_id") String jobId,
      @JsonProperty("status") String status,
      @JsonProperty("processed_count") int processedCount,
      @JsonProperty("error") @Nullable String error,
      @JsonProperty("pending_dirty_count") int pendingDirtyCount,
      @JsonProperty("created_at") long createdAt,
      @JsonProperty("updated_at") @Nullable Long updatedAt) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

  public GetPublishHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetPublishHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing get publish request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();

    var latestPublishJob = findLatestPublishJob(user);
    if (latestPublishJob == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var dirtyCount = countDirtySkus(user);

    return httpResponseFactory.ok(
        new PublishResponse(
            latestPublishJob.getJobId(),
            latestPublishJob.getStatus(),
            latestPublishJob.getProcessedCount() != null ? latestPublishJob.getProcessedCount() : 0,
            latestPublishJob.getError(),
            dirtyCount,
            latestPublishJob.getCreatedAt() != null
                ? latestPublishJob.getCreatedAt().getEpochSecond()
                : 0,
            latestPublishJob.getUpdatedAt() != null
                ? latestPublishJob.getUpdatedAt().getEpochSecond()
                : null));
  }

  private TcgInventoryItem findLatestPublishJob(String user) {
    var queryConditional =
        QueryConditional.sortBeginsWith(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk(user))
                .sortValue(TcgInventoryItem.JOB_PREFIX)
                .build());

    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(queryConditional)
            .scanIndexForward(false)
            .build();

    return tcgInventoryTable.query(request).stream()
        .flatMap(page -> page.items().stream())
        .filter(item -> "publish".equals(item.getJobType()))
        .findFirst()
        .orElse(null);
  }

  private int countDirtySkus(String user) {
    var queryConditional =
        QueryConditional.sortBeginsWith(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatGsi1pk(user))
                .sortValue(TcgInventoryItem.SKU_PREFIX)
                .build());

    var request = QueryEnhancedRequest.builder().queryConditional(queryConditional).build();

    return (int)
        tcgInventoryTable.index(TcgInventoryItem.GSI1_NAME).query(request).stream()
            .flatMap(page -> page.items().stream())
            .count();
  }
}
