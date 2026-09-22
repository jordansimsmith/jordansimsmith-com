package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetScanHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(GetScanHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  record ScanSummaryResponse(
      @JsonProperty("scan_id") String scanId,
      @JsonProperty("status") String status,
      @JsonProperty("condition") String condition,
      @JsonProperty("finish") String finish,
      @JsonProperty("row_count") int rowCount,
      @JsonProperty("processed_count") int processedCount,
      @JsonProperty("error") @Nullable String error,
      @JsonProperty("import_id") @Nullable String importId,
      @JsonProperty("created_at") long createdAt) {}

  record ScanSuggestionResponse(
      @JsonProperty("scryfall_id") String scryfallId,
      @JsonProperty("name") String name,
      @JsonProperty("score") double score) {}

  record ScanRowResponse(
      @JsonProperty("scan_position") int scanPosition,
      @JsonProperty("filename") String filename,
      @JsonProperty("size_bytes") long sizeBytes,
      @JsonProperty("uploaded") boolean uploaded,
      @JsonProperty("upload_url") @Nullable String uploadUrl,
      @JsonProperty("upload_headers") @Nullable Map<String, String> uploadHeaders,
      @JsonProperty("status") @Nullable String status,
      @JsonProperty("needs_review") boolean needsReview,
      @JsonProperty("suggestions") List<ScanSuggestionResponse> suggestions,
      @JsonProperty("source_url") @Nullable String sourceUrl,
      @JsonProperty("error") @Nullable String error) {}

  record ScanDetailResponse(
      @JsonProperty("scan_id") String scanId,
      @JsonProperty("status") String status,
      @JsonProperty("condition") String condition,
      @JsonProperty("finish") String finish,
      @JsonProperty("row_count") int rowCount,
      @JsonProperty("processed_count") int processedCount,
      @JsonProperty("error") @Nullable String error,
      @JsonProperty("import_id") @Nullable String importId,
      @JsonProperty("created_at") long createdAt,
      @JsonProperty("rows") List<ScanRowResponse> rows) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final TcgInventoryItemRepository tcgInventoryItemRepository;

  public GetScanHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetScanHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryItemRepository = factory.tcgInventoryItemRepository();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing get scan request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    Map<String, String> pathParameters = event.getPathParameters();
    var scanId = pathParameters.get("scan_id");
    var scanItem = tcgInventoryItemRepository.getScan(user, scanId);
    if (scanItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var rowItems = tcgInventoryItemRepository.findScanRows(user, scanId);
    return httpResponseFactory.ok(toDetail(scanItem, rowItems));
  }

  private static ScanDetailResponse toDetail(
      TcgInventoryItem item, List<TcgInventoryItem> rowItems) {
    var rows = rowItems.stream().map(GetScanHandler::toRow).toList();
    var summary = toSummary(item);
    return new ScanDetailResponse(
        summary.scanId(),
        summary.status(),
        summary.condition(),
        summary.finish(),
        summary.rowCount(),
        summary.processedCount(),
        summary.error(),
        summary.importId(),
        summary.createdAt(),
        rows);
  }

  private static ScanSummaryResponse toSummary(TcgInventoryItem item) {
    return new ScanSummaryResponse(
        item.getScanId(),
        item.getStatus(),
        item.getCondition(),
        item.getFinish(),
        item.getRowCount() != null ? item.getRowCount() : 0,
        item.getProcessedCount() != null ? item.getProcessedCount() : 0,
        item.getError(),
        item.getImportId(),
        item.getCreatedAt() != null ? item.getCreatedAt().getEpochSecond() : 0);
  }

  private static ScanRowResponse toRow(TcgInventoryItem item) {
    var status = publicStatus(item.getStatus());
    var sizeBytes = item.getSizeBytes() != null ? item.getSizeBytes() : 0;
    return new ScanRowResponse(
        item.getScanPosition() != null ? item.getScanPosition() : 0,
        item.getFilename(),
        sizeBytes,
        false,
        null,
        null,
        status,
        Boolean.TRUE.equals(item.getNeedsReview()) || "needs_review".equals(status),
        List.of(),
        null,
        item.getError());
  }

  @Nullable
  private static String publicStatus(@Nullable String status) {
    return "suggested".equals(status) || "needs_review".equals(status) ? status : null;
  }
}
