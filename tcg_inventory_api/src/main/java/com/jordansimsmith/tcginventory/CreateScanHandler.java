package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreateScanHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(CreateScanHandler.class);
  private static final int MAX_SCAN_FILES = 200;
  private static final long MAX_SCAN_FILE_BYTES = 1024 * 1024;
  private static final Set<String> VALID_FINISHES = Set.of("normal", "foil", "etched");

  record ScanFileRequest(
      @JsonProperty("filename") @Nullable String filename,
      @JsonProperty("size_bytes") @Nullable Long sizeBytes) {}

  record CreateScanRequest(
      @JsonProperty("condition") @Nullable String condition,
      @JsonProperty("finish") @Nullable String finish,
      @JsonProperty("files") @Nullable List<ScanFileRequest> files) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  record ScanUploadSlotResponse(
      @JsonProperty("scan_position") int scanPosition,
      @JsonProperty("filename") String filename,
      @JsonProperty("size_bytes") long sizeBytes,
      @JsonProperty("uploaded") boolean uploaded,
      @JsonProperty("upload_url") @Nullable String uploadUrl,
      @JsonProperty("upload_headers") @Nullable Map<String, String> uploadHeaders) {}

  record CreateScanResponse(
      @JsonProperty("scan_id") String scanId,
      @JsonProperty("rows") List<ScanUploadSlotResponse> rows) {}

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final TcgInventoryItemRepository tcgInventoryItemRepository;
  private final UlidGenerator ulidGenerator;

  public CreateScanHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  CreateScanHandler(TcgInventoryFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryItemRepository = factory.tcgInventoryItemRepository();
    this.ulidGenerator = factory.ulidGenerator();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing create scan request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();

    CreateScanRequest request;
    JsonNode requestNode;
    try {
      requestNode = objectMapper.readTree(event.getBody());
      request = objectMapper.treeToValue(requestNode, CreateScanRequest.class);
    } catch (Exception e) {
      return httpResponseFactory.badRequest(new ErrorResponse("invalid request body"));
    }

    var validationError = validate(request);
    if (validationError != null) {
      return httpResponseFactory.badRequest(new ErrorResponse(validationError));
    }
    if (!hasIntegralFileSizes(requestNode)) {
      return httpResponseFactory.badRequest(
          new ErrorResponse("scan files must be between 1 byte and 1 MiB"));
    }

    var files = new ArrayList<>(request.files());
    files.sort(Comparator.comparing(ScanFileRequest::filename));

    var now = clock.now();
    var scanId = ulidGenerator.generate();
    var scanItem =
        TcgInventoryItem.createScan(
            user, scanId, request.condition(), request.finish(), files.size(), now);
    var rowItems = new ArrayList<TcgInventoryItem>();
    for (int index = 0; index < files.size(); index++) {
      var file = files.get(index);
      rowItems.add(
          TcgInventoryItem.createScanRow(
              user, scanId, index + 1, file.filename(), file.sizeBytes()));
    }

    tcgInventoryItemRepository.createScan(scanItem, rowItems);
    return httpResponseFactory.created(toResponse(scanItem, rowItems));
  }

  private static CreateScanResponse toResponse(
      TcgInventoryItem scanItem, List<TcgInventoryItem> rowItems) {
    var rows = rowItems.stream().map(CreateScanHandler::toUploadSlot).toList();
    return new CreateScanResponse(scanItem.getScanId(), rows);
  }

  private static ScanUploadSlotResponse toUploadSlot(TcgInventoryItem item) {
    var sizeBytes = item.getSizeBytes() != null ? item.getSizeBytes() : 0;
    return new ScanUploadSlotResponse(
        item.getScanPosition() != null ? item.getScanPosition() : 0,
        item.getFilename(),
        sizeBytes,
        false,
        null,
        null);
  }

  @Nullable
  private static String validate(@Nullable CreateScanRequest request) {
    if (request == null) {
      return "invalid request body";
    }
    if (request.condition() == null || !isValidCondition(request.condition())) {
      return "invalid scan condition";
    }
    if (request.finish() == null || !VALID_FINISHES.contains(request.finish())) {
      return "invalid scan finish";
    }
    if (request.files() == null
        || request.files().isEmpty()
        || request.files().size() > MAX_SCAN_FILES) {
      return "a scan must contain between 1 and 200 files";
    }

    var filenames = new HashSet<String>();
    for (var file : request.files()) {
      if (file == null
          || file.filename() == null
          || file.filename().isBlank()
          || !file.filename().matches("(?i).*\\.jpe?g$")) {
        return "scan files must be JPEG images";
      }
      if (!isAscii(file.filename())) {
        return "scan filenames must use ASCII characters";
      }
      if (!filenames.add(file.filename())) {
        return "scan filenames must be unique";
      }
      if (file.sizeBytes() == null
          || file.sizeBytes() <= 0
          || file.sizeBytes() > MAX_SCAN_FILE_BYTES) {
        return "scan files must be between 1 byte and 1 MiB";
      }
    }
    return null;
  }

  private static boolean isValidCondition(String condition) {
    try {
      Condition.valueOf(condition);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean hasIntegralFileSizes(JsonNode requestNode) {
    var files = requestNode.get("files");
    if (files == null || !files.isArray()) {
      return true;
    }
    for (var file : files) {
      var sizeBytes = file.get("size_bytes");
      if (sizeBytes != null && !sizeBytes.isIntegralNumber()) {
        return false;
      }
    }
    return true;
  }

  private static boolean isAscii(String value) {
    return value.chars().allMatch(character -> character <= 0x7f);
  }
}
