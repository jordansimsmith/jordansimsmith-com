package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

public class DeleteScanRowHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(DeleteScanRowHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final TcgInventoryRepository tcgInventoryRepository;
  private final S3Client s3Client;

  public DeleteScanRowHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  DeleteScanRowHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryRepository = factory.tcgInventoryRepository();
    this.s3Client = factory.s3Client();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing delete scan row request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var pathParameters = event.getPathParameters();
    var scanId = pathParameters.get("scan_id");
    var scanPosition = Integer.parseInt(pathParameters.get("scan_position"));
    var scanItem = tcgInventoryRepository.getScan(user, scanId);
    if (scanItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }
    if (!"reviewing".equals(scanItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("scan is not in a deletable status"));
    }

    var rowItem = tcgInventoryRepository.getScanRow(user, scanId, scanPosition);
    if (rowItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    if (!tcgInventoryRepository.deleteScanRow(user, scanId, scanPosition)) {
      var currentScan = tcgInventoryRepository.getScan(user, scanId);
      if (currentScan == null) {
        return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
      }
      if (!"reviewing".equals(currentScan.getStatus())) {
        return httpResponseFactory.conflict(new ErrorResponse("scan is not in a deletable status"));
      }
      var currentRow = tcgInventoryRepository.getScanRow(user, scanId, scanPosition);
      if (currentRow == null) {
        return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
      }
      return httpResponseFactory.conflict(new ErrorResponse("scan changed before row deletion"));
    }

    s3Client.deleteObject(
        DeleteObjectRequest.builder().bucket(ScanImages.BUCKET).key(rowItem.getS3Key()).build());
    return httpResponseFactory.noContent();
  }
}
