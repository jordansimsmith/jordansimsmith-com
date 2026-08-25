package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.ArrayList;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class CreateImportRowPhotoHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(CreateImportRowPhotoHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final S3Client s3Client;
  private final UlidGenerator ulidGenerator;

  public CreateImportRowPhotoHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  CreateImportRowPhotoHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
    this.s3Client = factory.s3Client();
    this.ulidGenerator = factory.ulidGenerator();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing create import row photo request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();

    var headers = event.getHeaders();
    var contentType = headers != null ? headers.get("content-type") : null;
    if (contentType == null || !contentType.startsWith("image/jpeg")) {
      return httpResponseFactory.badRequest(new ErrorResponse("Content-Type must be image/jpeg"));
    }

    var body = event.getBody();
    if (body == null || body.isBlank()) {
      return httpResponseFactory.badRequest(new ErrorResponse("JPEG body is required"));
    }

    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(body);
    } catch (IllegalArgumentException e) {
      return httpResponseFactory.badRequest(new ErrorResponse("JPEG body is required"));
    }

    if (bytes.length > Photos.MAX_UPLOAD_BYTES) {
      return httpResponseFactory.badRequest(new ErrorResponse("body exceeds 4 MB"));
    }

    var importId = event.getPathParameters().get("import_id");
    var position = Integer.parseInt(event.getPathParameters().get("position"));

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

    if (!"keep".equals(rowItem.getDecision())) {
      return httpResponseFactory.badRequest(
          new ErrorResponse("photos can only be created on keep rows"));
    }

    var photos =
        rowItem.getPhotos() == null
            ? new ArrayList<TcgInventoryItem.Photo>()
            : new ArrayList<>(rowItem.getPhotos());
    if (photos.size() >= Photos.MAX_PHOTOS) {
      return httpResponseFactory.badRequest(new ErrorResponse("a row may have at most 5 photos"));
    }

    var photoId = ulidGenerator.generate();
    s3Client.putObject(
        PutObjectRequest.builder()
            .bucket(Photos.BUCKET)
            .key(Photos.key(user, photoId))
            .contentType("image/jpeg")
            .build(),
        RequestBody.fromBytes(bytes));

    photos.add(TcgInventoryItem.Photo.create(photoId, null));
    rowItem.setPhotos(photos);
    tcgInventoryTable.putItem(rowItem);

    return httpResponseFactory.noContent();
  }
}
