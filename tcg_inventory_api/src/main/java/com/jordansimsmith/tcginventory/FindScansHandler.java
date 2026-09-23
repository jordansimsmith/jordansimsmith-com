package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.dynamodb.Continuations;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FindScansHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(FindScansHandler.class);
  private static final int DEFAULT_LIMIT = 20;

  record ScanSummaryResponse(
      @JsonProperty("scan_id") String scanId,
      @JsonProperty("status") String status,
      @JsonProperty("condition") String condition,
      @JsonProperty("finish") String finish,
      @JsonProperty("row_count") int rowCount,
      @JsonProperty("error") @Nullable String error,
      @JsonProperty("import_id") @Nullable String importId,
      @JsonProperty("created_at") long createdAt) {}

  record FindScansResponse(
      @JsonProperty("scans") List<ScanSummaryResponse> scans,
      @JsonProperty("next_continuation") @Nullable String nextContinuation) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final ObjectMapper objectMapper;
  private final TcgInventoryItemRepository tcgInventoryItemRepository;

  public FindScansHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  FindScansHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.objectMapper = factory.objectMapper();
    this.tcgInventoryItemRepository = factory.tcgInventoryItemRepository();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing find scans request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var queryParams = event.getQueryStringParameters();
    var continuation = queryParams != null ? queryParams.get("continuation") : null;
    var limitParam = queryParams != null ? queryParams.get("limit") : null;

    int limit = DEFAULT_LIMIT;
    if (limitParam != null) {
      try {
        limit = Integer.parseInt(limitParam);
      } catch (NumberFormatException e) {
        return httpResponseFactory.badRequest(new ErrorResponse("limit must be positive"));
      }
      if (limit <= 0) {
        return httpResponseFactory.badRequest(new ErrorResponse("limit must be positive"));
      }
    }

    var exclusiveStartKey = Continuations.decode(continuation, objectMapper);
    var page = tcgInventoryItemRepository.findScans(user, limit, exclusiveStartKey);
    var scans = page.items().stream().map(FindScansHandler::toSummary).toList();
    var nextContinuation = Continuations.encode(page.lastEvaluatedKey(), objectMapper);
    return httpResponseFactory.ok(new FindScansResponse(scans, nextContinuation));
  }

  private static ScanSummaryResponse toSummary(TcgInventoryItem item) {
    return new ScanSummaryResponse(
        item.getScanId(),
        item.getStatus(),
        item.getCondition(),
        item.getFinish(),
        item.getRowCount() != null ? item.getRowCount() : 0,
        item.getError(),
        item.getImportId(),
        item.getCreatedAt() != null ? item.getCreatedAt().getEpochSecond() : 0);
  }
}
