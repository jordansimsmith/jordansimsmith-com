package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
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
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class GetReportsHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetReportsHandler.class);

  record GenerationResponse(
      @JsonProperty("status") String status,
      @JsonProperty("error") @Nullable String error,
      @JsonProperty("started_at") long startedAt,
      @JsonProperty("finished_at") @Nullable Long finishedAt) {}

  record ReportsResponse(
      @JsonProperty("generated_at") long generatedAt,
      @JsonProperty("stale") boolean stale,
      @JsonProperty("generation") @Nullable GenerationResponse generation,
      @JsonProperty("report") @JsonRawValue String report) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

  public GetReportsHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetReportsHandler(TcgInventoryFactory factory) {
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing get reports request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();

    var reportItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk(user))
                .sortValue(TcgInventoryItem.formatReportSk())
                .build());

    if (reportItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var latestAuditUlid = findLatestAuditUlid(user);
    var stale =
        (latestAuditUlid != null
                && (reportItem.getAsOfAuditUlid() == null
                    || latestAuditUlid.compareTo(reportItem.getAsOfAuditUlid()) > 0))
            || clock.now().isAfter(reportItem.getUpdatedAt().plus(Duration.ofHours(24)));

    var latestReportJob = findLatestReportJob(user);
    GenerationResponse generation = null;
    if (latestReportJob != null) {
      var status = latestReportJob.getStatus();
      var terminal = "succeeded".equals(status) || "failed".equals(status);
      var finishedAt =
          terminal && latestReportJob.getUpdatedAt() != null
              ? latestReportJob.getUpdatedAt().getEpochSecond()
              : null;
      generation =
          new GenerationResponse(
              status,
              latestReportJob.getError(),
              latestReportJob.getCreatedAt() != null
                  ? latestReportJob.getCreatedAt().getEpochSecond()
                  : 0,
              finishedAt);
    }

    var generatedAt =
        reportItem.getUpdatedAt() != null ? reportItem.getUpdatedAt().getEpochSecond() : 0;

    return httpResponseFactory.ok(
        new ReportsResponse(generatedAt, stale, generation, reportItem.getReport()));
  }

  private String findLatestAuditUlid(String user) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(TcgInventoryItem.formatAuditPk(user)).build()))
            .scanIndexForward(false)
            .limit(1)
            .build();

    return tcgInventoryTable.query(request).stream()
        .flatMap(page -> page.items().stream())
        .findFirst()
        .map(TcgInventoryItem::getSk)
        .orElse(null);
  }

  private TcgInventoryItem findLatestReportJob(String user) {
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
        .filter(item -> "report".equals(item.getJobType()))
        .findFirst()
        .orElse(null);
  }
}
