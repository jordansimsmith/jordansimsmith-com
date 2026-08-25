package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

public class DeleteImportRowPhotoHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(DeleteImportRowPhotoHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final S3Client s3Client;

  public DeleteImportRowPhotoHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  DeleteImportRowPhotoHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
    this.s3Client = factory.s3Client();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing delete import row photo request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var importId = event.getPathParameters().get("import_id");
    var position = Integer.parseInt(event.getPathParameters().get("position"));
    var photoId = event.getPathParameters().get("photo_id");

    var importItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk(user))
                .sortValue(TcgInventoryItem.formatImportSk(importId))
                .build());
    if (importItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    if (!"review".equals(importItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("import is not in review status"));
    }

    var rowItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatImportRowPk(user, importId))
                .sortValue(TcgInventoryItem.formatImportRowSk(position))
                .build());
    if (rowItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var existing = rowItem.getPhotos();
    if (existing == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var remaining = new ArrayList<TcgInventoryItem.Photo>();
    var found = false;
    for (var photo : existing) {
      if (photoId.equals(photo.getPhotoId())) {
        found = true;
      } else {
        remaining.add(photo);
      }
    }
    if (!found) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    s3Client.deleteObject(
        DeleteObjectRequest.builder().bucket(Photos.BUCKET).key(Photos.key(user, photoId)).build());

    rowItem.setPhotos(remaining);
    tcgInventoryTable.putItem(rowItem);

    return httpResponseFactory.noContent();
  }
}
