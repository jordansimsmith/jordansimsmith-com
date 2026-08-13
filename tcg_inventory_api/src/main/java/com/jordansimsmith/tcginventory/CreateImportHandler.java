package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

public class CreateImportHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(CreateImportHandler.class);

  record ImportSummaryResponse(
      @JsonProperty("import_id") String importId,
      @JsonProperty("filename") String filename,
      @JsonProperty("status") String status,
      @JsonProperty("row_count") int rowCount,
      @JsonProperty("keep_count") int keepCount,
      @JsonProperty("discard_count") int discardCount,
      @JsonProperty("review_count") int reviewCount,
      @JsonProperty("appraisal_error") @Nullable String appraisalError,
      @JsonProperty("created_at") long createdAt) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final QueueClient<JobMessage> jobsQueue;
  private final UlidGenerator ulidGenerator;

  public CreateImportHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  CreateImportHandler(TcgInventoryFactory factory) {
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
    this.jobsQueue = factory.jobsQueue();
    this.ulidGenerator = factory.ulidGenerator();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing create import request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();

    var headers = event.getHeaders();
    var contentType = headers != null ? headers.get("content-type") : null;
    if (contentType == null || !contentType.startsWith("text/csv")) {
      return httpResponseFactory.badRequest(new ErrorResponse("Content-Type must be text/csv"));
    }

    var queryParams = event.getQueryStringParameters();
    var filename = queryParams != null ? queryParams.get("filename") : null;
    if (filename == null || filename.isBlank()) {
      return httpResponseFactory.badRequest(
          new ErrorResponse("filename query parameter is required"));
    }

    var csv = event.getBody();
    if (csv == null || csv.isBlank()) {
      return httpResponseFactory.badRequest(new ErrorResponse("CSV body is required"));
    }

    List<ManaBoxCsvParser.ParsedRow> parsedRows;
    try {
      parsedRows = ManaBoxCsvParser.parse(csv);
    } catch (IllegalArgumentException e) {
      return httpResponseFactory.badRequest(new ErrorResponse(e.getMessage()));
    }

    var reversed = new ArrayList<>(parsedRows);
    Collections.reverse(reversed);

    var now = clock.now();
    var importId = ulidGenerator.generate();
    var jobId = ulidGenerator.generate();

    int totalRows = reversed.stream().mapToInt(ManaBoxCsvParser.ParsedRow::quantity).sum();

    var importItem = new TcgInventoryItem();
    importItem.setPk(TcgInventoryItem.formatUserPk(user));
    importItem.setSk(TcgInventoryItem.formatImportSk(importId));
    importItem.setImportId(importId);
    importItem.setFilename(filename);
    importItem.setStatus("appraising");
    importItem.setRowCount(totalRows);
    importItem.setKeepCount(0);
    importItem.setDiscardCount(0);
    importItem.setReviewCount(0);
    importItem.setJobId(jobId);
    importItem.setCreatedAt(now);
    tcgInventoryTable.putItem(importItem);

    int position = 0;
    for (var parsedRow : reversed) {
      for (int copy = 0; copy < parsedRow.quantity(); copy++) {
        position++;
        var rowItem = new TcgInventoryItem();
        rowItem.setPk(TcgInventoryItem.formatImportRowPk(user, importId));
        rowItem.setSk(TcgInventoryItem.formatImportRowSk(position));
        rowItem.setPosition(position);
        rowItem.setName(parsedRow.name());
        rowItem.setSetCode(parsedRow.setCode());
        rowItem.setSetName(parsedRow.setName());
        rowItem.setCollectorNumber(parsedRow.collectorNumber());
        rowItem.setFinish(parsedRow.finish());
        rowItem.setCondition(parsedRow.condition());
        rowItem.setScryfallId(parsedRow.scryfallId());
        rowItem.setLanguage(parsedRow.language());
        tcgInventoryTable.putItem(rowItem);
      }
    }

    var jobItem = new TcgInventoryItem();
    jobItem.setPk(TcgInventoryItem.formatUserPk(user));
    jobItem.setSk(TcgInventoryItem.formatJobSk(jobId));
    jobItem.setJobId(jobId);
    jobItem.setJobType("appraise");
    jobItem.setStatus("queued");
    jobItem.setImportId(importId);
    jobItem.setCreatedAt(now);
    tcgInventoryTable.putItem(jobItem);

    jobsQueue.send(new JobMessage(user, jobId, "appraise"));

    return httpResponseFactory.ok(
        new ImportSummaryResponse(
            importId, filename, "appraising", totalRows, 0, 0, 0, null, now.getEpochSecond()));
  }
}
