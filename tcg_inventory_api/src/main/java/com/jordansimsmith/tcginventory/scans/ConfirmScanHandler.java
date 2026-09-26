package com.jordansimsmith.tcginventory.scans;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobMessage;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.imports.ImportItem;
import com.jordansimsmith.tcginventory.imports.ImportRowItem;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

public class ConfirmScanHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConfirmScanHandler.class);

  record ConfirmScanRow(
      @JsonProperty("scan_position") @Nullable Integer scanPosition,
      @JsonProperty("external_source") @Nullable String externalSource,
      @JsonProperty("external_id") @Nullable String externalId,
      @JsonProperty("name") @Nullable String name,
      @JsonProperty("set_code") @Nullable String setCode,
      @JsonProperty("set_name") @Nullable String setName,
      @JsonProperty("collector_number") @Nullable String collectorNumber) {}

  record ConfirmScanRequest(@JsonProperty("rows") @Nullable List<ConfirmScanRow> rows) {}

  record ConfirmScanResponse(
      @JsonProperty("scan_id") String scanId,
      @JsonProperty("status") String status,
      @JsonProperty("import_id") String importId) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final ScanRepository scanRepository;
  private final DynamoDbTable<ImportItem> importTable;
  private final DynamoDbTable<ImportRowItem> importRowTable;
  private final DynamoDbTable<JobItem> jobTable;
  private final QueueClient<JobMessage> jobsQueue;
  private final UlidGenerator ulidGenerator;

  public ConfirmScanHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  ConfirmScanHandler(TcgInventoryFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.scanRepository =
        new ScanRepository(
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ScanItem.class),
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ScanRowItem.class),
            factory.dynamoDbClient(),
            factory.clock());
    this.importTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportItem.class);
    this.importRowTable =
        TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportRowItem.class);
    this.jobTable = factory.jobTable();
    this.jobsQueue = factory.jobsQueue();
    this.ulidGenerator = factory.ulidGenerator();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing confirm scan request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var scanId = event.getPathParameters().get("scan_id");
    var scanItem = scanRepository.getScan(user, scanId);
    if (scanItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    if ("confirmed".equals(scanItem.getStatus())) {
      if (scanItem.getImportId() == null) {
        throw new IllegalStateException("confirmed scan is missing import identifier");
      }
      return confirmed(scanItem);
    }
    if (!"reviewing".equals(scanItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("scan is not in reviewing status"));
    }
    ConfirmScanRequest request;
    try {
      request = objectMapper.readValue(event.getBody(), ConfirmScanRequest.class);
    } catch (Exception e) {
      return httpResponseFactory.badRequest(new ErrorResponse("invalid request body"));
    }

    var scanRows = scanRepository.findScanRows(user, scanId);
    var orderedRows = orderRows(request);
    try {
      validate(orderedRows, scanRows);
    } catch (IllegalArgumentException e) {
      return httpResponseFactory.badRequest(new ErrorResponse(e.getMessage()));
    }

    var importId = ulidGenerator.generate();
    var jobId = ulidGenerator.generate();
    var now = clock.now();
    var importItem =
        ImportItem.create(
            user, scanItem.getGame(), importId, scanId + ".scan", orderedRows.size(), jobId, now);

    importTable.putItem(importItem);
    for (int index = 0; index < orderedRows.size(); index++) {
      var selected = orderedRows.get(index);
      importRowTable.putItem(
          ImportRowItem.create(
              user,
              importId,
              index + 1,
              selected.name(),
              selected.setCode(),
              selected.setName(),
              selected.collectorNumber(),
              scanItem.getFinish(),
              scanItem.getCondition(),
              selected.externalSource(),
              selected.externalId(),
              "en"));
    }

    var jobItem = JobItem.create(user, jobId, "appraise", importId, now);
    jobTable.putItem(jobItem);
    var jobMessage = new JobMessage(user, jobId, "appraise");
    jobsQueue.send(jobMessage, user, jobMessage.deduplicationId(0));

    if (!scanRepository.transitionScanToConfirmed(user, scanId, importId)) {
      var currentScan = scanRepository.getScan(user, scanId);
      if (currentScan == null) {
        return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
      }
      if ("confirmed".equals(currentScan.getStatus()) && currentScan.getImportId() != null) {
        return confirmed(currentScan);
      }
      return httpResponseFactory.conflict(new ErrorResponse("scan changed before confirmation"));
    }

    scanItem.setImportId(importId);
    scanItem.setStatus("confirmed");
    return confirmed(scanItem);
  }

  private APIGatewayV2HTTPResponse confirmed(ScanItem scanItem) {
    return httpResponseFactory.ok(
        new ConfirmScanResponse(scanItem.getScanId(), "confirmed", scanItem.getImportId()));
  }

  private static List<ConfirmScanRow> orderRows(@Nullable ConfirmScanRequest request) {
    if (request == null || request.rows() == null) {
      return List.of();
    }
    return request.rows().stream()
        .sorted(
            Comparator.nullsFirst(
                Comparator.comparing(
                    ConfirmScanRow::scanPosition, Comparator.nullsFirst(Integer::compareTo))))
        .toList();
  }

  private static void validate(List<ConfirmScanRow> orderedRows, List<ScanRowItem> scanRows) {
    if (orderedRows.isEmpty() || orderedRows.size() != scanRows.size()) {
      throw new IllegalArgumentException("every retained scan row must be selected exactly once");
    }
    var positions = new HashSet<Integer>();
    for (var row : orderedRows) {
      if (row == null
          || row.scanPosition() == null
          || row.scanPosition() <= 0
          || !positions.add(row.scanPosition())
          || isBlank(row.externalSource())
          || isBlank(row.externalId())
          || isBlank(row.name())
          || isBlank(row.setCode())
          || isBlank(row.setName())
          || isBlank(row.collectorNumber())) {
        throw new IllegalArgumentException("every retained scan row must be selected exactly once");
      }
    }
    for (int index = 0; index < scanRows.size(); index++) {
      if (!Integer.valueOf(scanRows.get(index).getScanPosition())
          .equals(orderedRows.get(index).scanPosition())) {
        throw new IllegalArgumentException("every retained scan row must be selected exactly once");
      }
    }
  }

  private static boolean isBlank(@Nullable String value) {
    return value == null || value.isBlank();
  }
}
