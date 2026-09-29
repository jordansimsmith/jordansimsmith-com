package com.jordansimsmith.tcginventory.imports;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.tcginventory.Photos;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

public class GetImportHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetImportHandler.class);

  record PhotoResponse(@JsonProperty("photo_id") String photoId, @JsonProperty("url") String url) {}

  record ImportRowResponse(
      @JsonProperty("position") int position,
      @JsonProperty("name") String name,
      @JsonProperty("set_code") String setCode,
      @JsonProperty("set_name") String setName,
      @JsonProperty("collector_number") String collectorNumber,
      @JsonProperty("finish") String finish,
      @JsonProperty("condition") String condition,
      @JsonProperty("external_source") String externalSource,
      @JsonProperty("external_id") String externalId,
      @JsonProperty("decision") @Nullable String decision,
      @JsonProperty("decision_reason") @Nullable String decisionReason,
      @JsonProperty("market_price") @Nullable String marketPrice,
      @JsonProperty("suggested_price") @Nullable String suggestedPrice,
      @JsonProperty("photos") List<PhotoResponse> photos,
      @JsonProperty("needs_photos") boolean needsPhotos) {}

  record ImportDetailResponse(
      @JsonProperty("import_id") String importId,
      @JsonProperty("game") String game,
      @JsonProperty("filename") String filename,
      @JsonProperty("status") String status,
      @JsonProperty("row_count") int rowCount,
      @JsonProperty("appraisal_error") @Nullable String appraisalError,
      @JsonProperty("confirmation_error") @Nullable String confirmationError,
      @JsonProperty("confirmation_result") @Nullable ImportConfirmationResult confirmationResult,
      @JsonProperty("created_at") long createdAt,
      @JsonProperty("total_suggested_price") String totalSuggestedPrice,
      @JsonProperty("rows") List<ImportRowResponse> rows) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final ImportRepository importRepository;
  private final S3Presigner s3Presigner;

  public GetImportHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetImportHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    var dynamoDbClient = factory.dynamoDbClient();
    var inventoryRepository =
        new InventoryRepository(
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), UnitItem.class),
            dynamoDbClient,
            factory.clock(),
            factory.ulidGenerator());
    this.importRepository =
        new ImportRepository(
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportItem.class),
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportRowItem.class),
            factory.jobTable(),
            inventoryRepository,
            dynamoDbClient,
            factory.clock());
    this.s3Presigner = factory.s3Presigner();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing get import request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var importId = event.getPathParameters().get("import_id");

    var importItem = importRepository.getImport(user, importId);
    if (importItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }
    var rowItems = importRepository.findRows(user, importId);

    var rows =
        rowItems.stream()
            .map(
                item ->
                    new ImportRowResponse(
                        item.getPosition() != null ? item.getPosition() : 0,
                        item.getName(),
                        item.getSetCode(),
                        item.getSetName(),
                        item.getCollectorNumber(),
                        item.getFinish(),
                        item.getCondition(),
                        item.getExternalSource(),
                        item.getExternalId(),
                        item.getDecision(),
                        item.getDecisionReason(),
                        item.getMarketPrice(),
                        item.getSuggestedPrice(),
                        toPhotoResponses(user, item.getPhotos()),
                        Photos.needsPhotos(
                            item.getDecision(),
                            item.getSuggestedPrice(),
                            item.getPhotos() == null ? 0 : item.getPhotos().size())))
            .toList();

    return httpResponseFactory.ok(
        new ImportDetailResponse(
            importItem.getImportId(),
            importItem.getGame(),
            importItem.getFilename(),
            importItem.getStatus(),
            importItem.getRowCount() != null ? importItem.getRowCount() : 0,
            importItem.getError(),
            importRepository.getConfirmationError(user, importItem),
            "confirmed".equals(importItem.getStatus())
                ? ImportConfirmationResult.from(
                    importItem,
                    rowItems.stream().filter(row -> "keep".equals(row.getDecision())).toList())
                : null,
            importItem.getCreatedAt() != null ? importItem.getCreatedAt().getEpochSecond() : 0,
            ImportRows.totalSuggestedPrice(rowItems),
            rows));
  }

  private List<PhotoResponse> toPhotoResponses(String user, List<ImportRowItem.Photo> photos) {
    if (photos == null) {
      return List.of();
    }
    return photos.stream()
        .map(
            photo ->
                new PhotoResponse(
                    photo.getPhotoId(),
                    Photos.presignedGetUrl(s3Presigner, user, photo.getPhotoId())))
        .toList();
  }
}
