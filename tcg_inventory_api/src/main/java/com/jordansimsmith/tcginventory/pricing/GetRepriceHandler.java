package com.jordansimsmith.tcginventory.pricing;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class GetRepriceHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetRepriceHandler.class);

  public record RepriceResponse(
      @JsonProperty("job_id") String jobId,
      @JsonProperty("status") String status,
      @JsonProperty("processed_sku_count") int processedSkuCount,
      @JsonProperty("error") @Nullable String error,
      @JsonProperty("started_at") long startedAt,
      @JsonProperty("finished_at") @Nullable Long finishedAt) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<JobItem> jobTable;

  public GetRepriceHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetRepriceHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.jobTable = factory.jobTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing get reprice request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var latestRepriceJob = findLatestRepriceJob(user);
    if (latestRepriceJob == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var status = latestRepriceJob.getStatus();
    var terminal = "succeeded".equals(status) || "failed".equals(status);
    var finishedAt =
        terminal && latestRepriceJob.getUpdatedAt() != null
            ? latestRepriceJob.getUpdatedAt().getEpochSecond()
            : null;

    return httpResponseFactory.ok(
        new RepriceResponse(
            latestRepriceJob.getJobId(),
            status,
            latestRepriceJob.getProcessedCount() != null ? latestRepriceJob.getProcessedCount() : 0,
            latestRepriceJob.getError(),
            latestRepriceJob.getCreatedAt() != null
                ? latestRepriceJob.getCreatedAt().getEpochSecond()
                : 0,
            finishedAt));
  }

  private JobItem findLatestRepriceJob(String user) {
    var queryConditional =
        QueryConditional.sortBeginsWith(
            Key.builder()
                .partitionValue(JobItem.formatPk(user))
                .sortValue(JobItem.JOB_PREFIX)
                .build());

    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(queryConditional)
            .scanIndexForward(false)
            .consistentRead(true)
            .build();

    return jobTable.query(request).stream()
        .flatMap(page -> page.items().stream())
        .filter(item -> "reprice".equals(item.getJobType()))
        .findFirst()
        .orElse(null);
  }
}
