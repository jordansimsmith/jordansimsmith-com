package com.jordansimsmith.tcginventory.scans;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.tcginventory.ActiveJob;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobMessage;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final ScanRepository scanRepository;
  private final ActiveJob activeJob;
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
    this.activeJob = new ActiveJob(factory.jobTable());
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
      return httpResponseFactory.noContent();
    }
    if ("confirming".equals(scanItem.getStatus())) {
      return httpResponseFactory.accepted();
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

    // keep scan review data stable while a background job is running.
    if (activeJob.exists(user)) {
      return httpResponseFactory.conflict(new ErrorResponse("another job is in progress"));
    }

    for (var row : orderedRows) {
      scanRepository.updateScanRowSelection(
          user, scanId, row.scanPosition(), row.externalSource(), row.externalId());
    }

    var now = clock.now();
    var jobItem =
        JobItem.create(user, ulidGenerator.generate(), "scan_confirmation", null, null, now);
    jobItem.setScanId(scanId);

    if (!scanRepository.startScanConfirmation(user, scanId, jobItem)) {
      var currentScan = scanRepository.getScan(user, scanId);
      if (currentScan == null) {
        return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
      }
      if ("confirmed".equals(currentScan.getStatus())) {
        return httpResponseFactory.noContent();
      }
      if ("confirming".equals(currentScan.getStatus())) {
        return httpResponseFactory.accepted();
      }
      return httpResponseFactory.conflict(new ErrorResponse("scan changed before confirmation"));
    }

    var jobMessage = new JobMessage(user, jobItem.getJobId(), "scan_confirmation");
    jobsQueue.send(jobMessage, user, jobMessage.deduplicationId(0));
    return httpResponseFactory.accepted();
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
          || !positions.add(row.scanPosition())) {
        throw new IllegalArgumentException("every retained scan row must be selected exactly once");
      }
      if (Strings.isNullOrEmpty(row.externalSource())) {
        throw new IllegalArgumentException(
            "scan position %d: external_source is required".formatted(row.scanPosition()));
      }
      if (Strings.isNullOrEmpty(row.externalId())) {
        throw new IllegalArgumentException(
            "scan position %d: external_id is required".formatted(row.scanPosition()));
      }
      if (Strings.isNullOrEmpty(row.name())
          || Strings.isNullOrEmpty(row.setCode())
          || Strings.isNullOrEmpty(row.setName())
          || Strings.isNullOrEmpty(row.collectorNumber())) {
        throw new IllegalArgumentException(
            "scan position %d: card metadata is required".formatted(row.scanPosition()));
      }
    }
    for (int index = 0; index < scanRows.size(); index++) {
      if (!Integer.valueOf(scanRows.get(index).getScanPosition())
          .equals(orderedRows.get(index).scanPosition())) {
        throw new IllegalArgumentException("every retained scan row must be selected exactly once");
      }
    }
  }
}
