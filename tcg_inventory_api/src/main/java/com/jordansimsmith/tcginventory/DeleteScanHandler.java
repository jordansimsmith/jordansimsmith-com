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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

public class DeleteScanHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(DeleteScanHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final TcgInventoryRepository tcgInventoryRepository;
  private final S3Client s3Client;

  public DeleteScanHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  DeleteScanHandler(TcgInventoryFactory factory) {
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
      LOGGER.error("error processing delete scan request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var scanId = event.getPathParameters().get("scan_id");
    var scanItem = tcgInventoryRepository.getScan(user, scanId);
    if (scanItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }
    if (!isDeletableStatus(scanItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("scan is not in a deletable status"));
    }

    var rowItems = tcgInventoryRepository.findScanRows(user, scanId);
    if (!tcgInventoryRepository.deleteScan(user, scanId)) {
      var currentScan = tcgInventoryRepository.getScan(user, scanId);
      if (currentScan == null) {
        return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
      }
      return httpResponseFactory.conflict(new ErrorResponse("scan is not in a deletable status"));
    }

    tcgInventoryRepository.deleteScanRows(rowItems);
    deleteSourceObjects(rowItems);
    return httpResponseFactory.noContent();
  }

  private void deleteSourceObjects(List<ScanRowItem> rowItems) {
    if (rowItems.isEmpty()) {
      return;
    }

    var objects =
        rowItems.stream()
            .map(row -> ObjectIdentifier.builder().key(row.getS3Key()).build())
            .toList();
    var response =
        s3Client.deleteObjects(
            DeleteObjectsRequest.builder()
                .bucket(ScanImages.BUCKET)
                .delete(Delete.builder().objects(objects).quiet(true).build())
                .build());
    if (!response.errors().isEmpty()) {
      throw new RuntimeException("failed to delete one or more scan source objects");
    }
  }

  private static boolean isDeletableStatus(String status) {
    return "uploading".equals(status) || "identifying".equals(status) || "reviewing".equals(status);
  }
}
