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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class IdentifyScanHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(IdentifyScanHandler.class);

  record IdentifyScanResponse(
      @JsonProperty("scan_id") String scanId, @JsonProperty("status") String status) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final TcgInventoryRepository tcgInventoryRepository;
  private final S3Client s3Client;
  private final QueueClient<ScanMessage> scanQueue;

  public IdentifyScanHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  IdentifyScanHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryRepository = factory.tcgInventoryRepository();
    this.s3Client = factory.s3Client();
    this.scanQueue = factory.scanQueue();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing identify scan request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    Map<String, String> pathParameters = event.getPathParameters();
    var scanId = pathParameters.get("scan_id");
    var scanItem = tcgInventoryRepository.getScan(user, scanId);
    if (scanItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    if (!"uploading".equals(scanItem.getStatus())) {
      return accepted(scanItem);
    }

    var scanRows = tcgInventoryRepository.findScanRows(user, scanId);
    if (!areAllRowsUploaded(scanRows)) {
      return httpResponseFactory.conflict(
          new ErrorResponse("all scan files must be uploaded before identification"));
    }

    if (!tcgInventoryRepository.transitionScanToIdentifying(user, scanId)) {
      var currentScan = tcgInventoryRepository.getScan(user, scanId);
      if (currentScan == null) {
        return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
      }
      return accepted(currentScan);
    }

    scanQueue.send(new ScanMessage(user, scanId));
    return httpResponseFactory.accepted(new IdentifyScanResponse(scanId, "identifying"));
  }

  private boolean areAllRowsUploaded(List<ScanRowItem> scanRows) {
    for (var row : scanRows) {
      try {
        var head =
            s3Client.headObject(
                HeadObjectRequest.builder().bucket(ScanImages.BUCKET).key(row.getS3Key()).build());
        if (!Objects.equals(head.contentLength(), row.getSizeBytes())
            || !ScanImages.CONTENT_TYPE.equals(head.contentType())) {
          return false;
        }
      } catch (S3Exception e) {
        if (e.statusCode() == 404) {
          return false;
        }
        throw e;
      }
    }
    return true;
  }

  private APIGatewayV2HTTPResponse accepted(ScanItem scanItem) {
    return httpResponseFactory.accepted(
        new IdentifyScanResponse(scanItem.getScanId(), scanItem.getStatus()));
  }
}
