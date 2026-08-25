package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

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
      @JsonProperty("scryfall_id") String scryfallId,
      @JsonProperty("decision") @Nullable String decision,
      @JsonProperty("decision_reason") @Nullable String decisionReason,
      @JsonProperty("market_price") @Nullable String marketPrice,
      @JsonProperty("suggested_price") @Nullable String suggestedPrice,
      @JsonProperty("photos") List<PhotoResponse> photos,
      @JsonProperty("needs_photos") boolean needsPhotos) {}

  record ImportDetailResponse(
      @JsonProperty("import_id") String importId,
      @JsonProperty("filename") String filename,
      @JsonProperty("status") String status,
      @JsonProperty("row_count") int rowCount,
      @JsonProperty("appraisal_error") @Nullable String appraisalError,
      @JsonProperty("created_at") long createdAt,
      @JsonProperty("rows") List<ImportRowResponse> rows) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final S3Presigner s3Presigner;

  public GetImportHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetImportHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
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

    var importKey =
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatImportSk(importId))
            .build();

    var importItem = tcgInventoryTable.getItem(importKey);
    if (importItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var rowQueryConditional =
        QueryConditional.sortBeginsWith(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatImportRowPk(user, importId))
                .sortValue(TcgInventoryItem.ROW_PREFIX)
                .build());

    var rowRequest =
        QueryEnhancedRequest.builder()
            .queryConditional(rowQueryConditional)
            .scanIndexForward(true)
            .build();

    var rows =
        tcgInventoryTable.query(rowRequest).stream()
            .flatMap(page -> page.items().stream())
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
                        item.getScryfallId(),
                        item.getDecision(),
                        item.getDecisionReason(),
                        item.getMarketPrice(),
                        item.getSuggestedPrice(),
                        toPhotoResponses(user, item.getPhotos()),
                        "keep".equals(item.getDecision())
                            && item.getSuggestedPrice() != null
                            && (item.getPhotos() == null || item.getPhotos().isEmpty())
                            && new BigDecimal(item.getSuggestedPrice())
                                    .compareTo(new BigDecimal("20"))
                                >= 0))
            .toList();

    return httpResponseFactory.ok(
        new ImportDetailResponse(
            importItem.getImportId(),
            importItem.getFilename(),
            importItem.getStatus(),
            importItem.getRowCount() != null ? importItem.getRowCount() : 0,
            importItem.getError(),
            importItem.getCreatedAt() != null ? importItem.getCreatedAt().getEpochSecond() : 0,
            rows));
  }

  private List<PhotoResponse> toPhotoResponses(String user, List<TcgInventoryItem.Photo> photos) {
    if (photos == null) {
      return List.of();
    }
    return photos.stream()
        .map(
            photo ->
                new PhotoResponse(
                    photo.getPhotoId(),
                    s3Presigner
                        .presignGetObject(
                            GetObjectPresignRequest.builder()
                                .signatureDuration(Duration.ofMinutes(15))
                                .getObjectRequest(
                                    GetObjectRequest.builder()
                                        .bucket(Photos.BUCKET)
                                        .key(Photos.key(user, photo.getPhotoId()))
                                        .build())
                                .build())
                        .url()
                        .toString()))
        .toList();
  }
}
