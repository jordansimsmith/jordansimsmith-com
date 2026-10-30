package com.jordansimsmith.tcginventory.imports;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.photos.client.CreatePhotoRequest;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import java.util.ArrayList;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class CreateImportRowPhotoHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(CreateImportRowPhotoHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final ImportsRepository importsRepository;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<ImportItem> importTable;
  private final DynamoDbTable<ImportRowItem> importRowTable;
  private final PhotosService photosService;

  public CreateImportRowPhotoHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  CreateImportRowPhotoHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.importsRepository = new ImportsRepository(factory.dynamoDbClient(), factory.jobTable());
    this.httpResponseFactory = factory.httpResponseFactory();
    this.importTable = TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportItem.class);
    this.importRowTable =
        TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportRowItem.class);
    this.photosService = factory.photosService();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (ServiceException e) {
      return httpResponseFactory.serviceError(e);
    } catch (Exception e) {
      LOGGER.error("error processing create import row photo request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var ctx = requestContextFactory.createCtx(event);
    var user = ctx.user();

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

    var importId = event.getPathParameters().get("import_id");
    var position = Integer.parseInt(event.getPathParameters().get("position"));

    var importItem =
        importTable.getItem(
            Key.builder()
                .partitionValue(ImportItem.formatPk(user))
                .sortValue(ImportItem.formatSk(importId))
                .build());
    if (importItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    if (!"review".equals(importItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("import is not in review status"));
    }

    var rowItem =
        importRowTable.getItem(
            Key.builder()
                .partitionValue(ImportRowItem.formatPk(user, importId))
                .sortValue(ImportRowItem.formatSk(position))
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
            ? new ArrayList<ImportRowItem.Photo>()
            : new ArrayList<>(rowItem.getPhotos());
    if (photos.size() >= ImportPhotos.MAX_PHOTOS) {
      return httpResponseFactory.badRequest(new ErrorResponse("a row may have at most 5 photos"));
    }

    var photoId = photosService.createPhoto(ctx, new CreatePhotoRequest(bytes)).photoId();

    photos.add(ImportRowItem.Photo.create(photoId));
    importsRepository.updateRowPhotos(user, importId, position, photos);

    return httpResponseFactory.noContent();
  }
}
