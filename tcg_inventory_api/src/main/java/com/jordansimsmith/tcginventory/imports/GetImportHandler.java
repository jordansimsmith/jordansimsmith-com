package com.jordansimsmith.tcginventory.imports;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.inventory.InventoryLocation;
import com.jordansimsmith.tcginventory.photos.client.FindPhotosRequest;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

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
      @JsonProperty("created_at") long createdAt,
      @JsonProperty("total_suggested_price") String totalSuggestedPrice,
      @JsonProperty("unit_count") @Nullable Integer unitCount,
      @JsonProperty("placement_instructions") @Nullable
          List<PlacementInstruction> placementInstructions,
      @JsonProperty("rows") List<ImportRowResponse> rows) {}

  record PlacementInstruction(
      @JsonProperty("block") String block,
      @JsonProperty("from_location") String fromLocation,
      @JsonProperty("to_location") String toLocation,
      @JsonProperty("from_name") String fromName,
      @JsonProperty("to_name") String toName,
      @JsonProperty("unit_count") int unitCount) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<ImportItem> importTable;
  private final DynamoDbTable<ImportRowItem> importRowTable;
  private final PhotosService photosService;

  public GetImportHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetImportHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
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
      LOGGER.error("error processing get import request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var ctx = requestContextFactory.createCtx(event);
    var user = ctx.user();
    var importId = event.getPathParameters().get("import_id");

    var importKey =
        Key.builder()
            .partitionValue(ImportItem.formatPk(user))
            .sortValue(ImportItem.formatSk(importId))
            .build();

    var importItem = importTable.getItem(request -> request.key(importKey).consistentRead(true));
    if (importItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var rowQueryConditional =
        QueryConditional.sortBeginsWith(
            Key.builder()
                .partitionValue(ImportRowItem.formatPk(user, importId))
                .sortValue(ImportRowItem.ROW_PREFIX)
                .build());

    var rowRequest =
        QueryEnhancedRequest.builder()
            .queryConditional(rowQueryConditional)
            .scanIndexForward(true)
            .consistentRead(true)
            .build();

    var rowItems =
        importRowTable.query(rowRequest).stream().flatMap(page -> page.items().stream()).toList();
    var photoUrls = findPhotoUrls(ctx, rowItems);

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
                        item.getExternalId(),
                        item.getDecision(),
                        item.getDecisionReason(),
                        item.getMarketPrice(),
                        item.getSuggestedPrice(),
                        toPhotoResponses(item.getPhotos(), photoUrls),
                        ImportPhotos.needsPhotos(
                            item.getDecision(),
                            item.getSuggestedPrice(),
                            item.getPhotos() == null ? 0 : item.getPhotos().size())))
            .toList();

    List<PlacementInstruction> placementInstructions = null;
    Integer unitCount = null;
    if ("confirmed".equals(importItem.getStatus())) {
      var keepRows = rowItems.stream().filter(row -> "keep".equals(row.getDecision())).toList();
      unitCount = keepRows.size();
      placementInstructions = buildPlacementInstructions(keepRows);
    }

    return httpResponseFactory.ok(
        new ImportDetailResponse(
            importItem.getImportId(),
            importItem.getGame(),
            importItem.getFilename(),
            importItem.getStatus(),
            importItem.getRowCount() != null ? importItem.getRowCount() : 0,
            importItem.getError(),
            importItem.getCreatedAt() != null ? importItem.getCreatedAt().getEpochSecond() : 0,
            ImportRows.totalSuggestedPrice(rowItems),
            unitCount,
            placementInstructions,
            rows));
  }

  private List<PlacementInstruction> buildPlacementInstructions(List<ImportRowItem> keepRows) {
    var instructions = new ArrayList<PlacementInstruction>();
    if (keepRows.isEmpty()) {
      return instructions;
    }

    int currentBlockNumber = keepRows.get(0).getSequenceNumber() / 100;
    int blockStartIndex = 0;
    for (int index = 0; index < keepRows.size(); index++) {
      int blockNumber = keepRows.get(index).getSequenceNumber() / 100;
      if (blockNumber != currentBlockNumber) {
        instructions.add(
            buildPlacementInstruction(keepRows, blockStartIndex, index - 1, currentBlockNumber));
        currentBlockNumber = blockNumber;
        blockStartIndex = index;
      }
    }
    instructions.add(
        buildPlacementInstruction(
            keepRows, blockStartIndex, keepRows.size() - 1, currentBlockNumber));
    return List.copyOf(instructions);
  }

  private PlacementInstruction buildPlacementInstruction(
      List<ImportRowItem> rows, int startIndex, int endIndex, int blockNumber) {
    var firstRow = rows.get(startIndex);
    var lastRow = rows.get(endIndex);
    int firstSequenceNumber = firstRow.getSequenceNumber();
    int lastSequenceNumber = lastRow.getSequenceNumber();
    return new PlacementInstruction(
        InventoryLocation.formatBlock(blockNumber),
        InventoryLocation.formatLocation(firstSequenceNumber),
        InventoryLocation.formatLocation(lastSequenceNumber),
        firstRow.getName(),
        lastRow.getName(),
        endIndex - startIndex + 1);
  }

  private List<PhotoResponse> toPhotoResponses(
      List<ImportRowItem.Photo> photos, Map<String, String> photoUrls) {
    if (photos == null) {
      return List.of();
    }
    return photos.stream()
        .map(photo -> new PhotoResponse(photo.getPhotoId(), photoUrls.get(photo.getPhotoId())))
        .toList();
  }

  private Map<String, String> findPhotoUrls(RequestContext ctx, List<ImportRowItem> rows)
      throws ServiceException {
    var photoIds = new LinkedHashSet<String>();
    for (var row : rows) {
      if (row.getPhotos() != null) {
        row.getPhotos().forEach(photo -> photoIds.add(photo.getPhotoId()));
      }
    }

    var urls = new LinkedHashMap<String, String>();
    var orderedPhotoIds = new ArrayList<>(photoIds);
    for (int offset = 0; offset < orderedPhotoIds.size(); offset += 100) {
      var end = Math.min(offset + 100, orderedPhotoIds.size());
      var response =
          photosService.findPhotos(
              ctx, new FindPhotosRequest(orderedPhotoIds.subList(offset, end)));
      response.photos().forEach(photo -> urls.put(photo.photoId(), photo.url()));
    }
    return urls;
  }
}
