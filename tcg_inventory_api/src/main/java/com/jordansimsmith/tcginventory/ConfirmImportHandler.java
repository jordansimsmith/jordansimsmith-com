package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.time.Clock;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class ConfirmImportHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfirmImportHandler.class);

  record ConfirmResponse(
      @JsonProperty("import_id") String importId,
      @JsonProperty("status") String status,
      @JsonProperty("unit_count") int unitCount,
      @JsonProperty("total_suggested_price") String totalSuggestedPrice,
      @JsonProperty("first_sequence_number") int firstSequenceNumber,
      @JsonProperty("last_sequence_number") int lastSequenceNumber,
      @JsonProperty("placement_instructions") List<PlacementInstruction> placementInstructions) {}

  record PlacementInstruction(
      @JsonProperty("block") String block,
      @JsonProperty("from_location") String fromLocation,
      @JsonProperty("to_location") String toLocation,
      @JsonProperty("from_name") String fromName,
      @JsonProperty("to_name") String toName,
      @JsonProperty("unit_count") int unitCount) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final TcgInventoryItemRepository tcgInventoryItemRepository;

  public ConfirmImportHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  ConfirmImportHandler(TcgInventoryFactory factory) {
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
    this.tcgInventoryItemRepository = factory.tcgInventoryItemRepository();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing confirm import request", e);
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

    if (!"review".equals(importItem.getStatus()) && !"confirming".equals(importItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("import is not in review status"));
    }

    var keepRows = queryKeepRows(user, importId);
    long rowsNeedingPhotos =
        keepRows.stream()
            .filter(
                row ->
                    Photos.needsPhotos(row.getDecision(), row.getSuggestedPrice(), row.getPhotos()))
            .count();
    if (rowsNeedingPhotos > 0) {
      return httpResponseFactory.conflict(
          new ErrorResponse(rowsNeedingPhotos + " rows need photos before confirm"));
    }

    if ("review".equals(importItem.getStatus())) {
      importItem.setStatus("confirming");
      importItem.setUpdatedAt(clock.now());
      tcgInventoryTable.putItem(importItem);
    }

    var totalSuggestedPrice =
        keepRows.stream()
            .map(row -> new BigDecimal(row.getSuggestedPrice()))
            .reduce(new BigDecimal("0.00"), BigDecimal::add)
            .toPlainString();

    if (keepRows.isEmpty()) {
      importItem.setStatus("confirmed");
      importItem.setUpdatedAt(clock.now());
      tcgInventoryTable.putItem(importItem);
      return httpResponseFactory.ok(
          new ConfirmResponse(importId, "confirmed", 0, totalSuggestedPrice, 0, 0, List.of()));
    }

    int keepCount = keepRows.size();
    int firstSeq = allocateSequenceRange(user, keepCount, keepRows);
    int lastSeq = firstSeq + keepCount - 1;

    assignSequenceNumbers(keepRows, firstSeq);

    var skuGroups = groupBySkuId(keepRows);
    for (var entry : skuGroups.entrySet()) {
      confirmSkuChunk(user, importId, entry.getKey(), entry.getValue());
    }

    importItem.setStatus("confirmed");
    importItem.setUpdatedAt(clock.now());
    tcgInventoryTable.putItem(importItem);

    var placementInstructions = buildPlacementInstructions(keepRows);

    return httpResponseFactory.ok(
        new ConfirmResponse(
            importId,
            "confirmed",
            keepCount,
            totalSuggestedPrice,
            firstSeq,
            lastSeq,
            placementInstructions));
  }

  private List<TcgInventoryItem> queryKeepRows(String user, String importId) {
    var queryConditional =
        QueryConditional.sortBeginsWith(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatImportRowPk(user, importId))
                .sortValue(TcgInventoryItem.ROW_PREFIX)
                .build());

    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(queryConditional)
            .scanIndexForward(true)
            .build();

    return tcgInventoryTable.query(request).stream()
        .flatMap(page -> page.items().stream())
        .filter(row -> "keep".equals(row.getDecision()))
        .toList();
  }

  private int allocateSequenceRange(String user, int keepCount, List<TcgInventoryItem> keepRows) {
    var firstRowWithSeq =
        keepRows.stream().filter(r -> r.getSequenceNumber() != null).findFirst().orElse(null);
    if (firstRowWithSeq != null) {
      return keepRows.stream()
          .filter(r -> r.getSequenceNumber() != null)
          .mapToInt(TcgInventoryItem::getSequenceNumber)
          .min()
          .orElse(0);
    }

    return tcgInventoryItemRepository.allocateSequenceRange(user, keepCount);
  }

  private void assignSequenceNumbers(List<TcgInventoryItem> keepRows, int firstSeq) {
    int seq = firstSeq;
    for (var row : keepRows) {
      if (row.getSequenceNumber() != null) {
        seq++;
        continue;
      }
      row.setSequenceNumber(seq);
      tcgInventoryTable.putItem(row);
      seq++;
    }
  }

  private Map<String, List<TcgInventoryItem>> groupBySkuId(List<TcgInventoryItem> keepRows) {
    var groups = new HashMap<String, List<TcgInventoryItem>>();
    for (var row : keepRows) {
      var skuId = row.getScryfallId() + "#" + row.getFinish() + "#" + row.getCondition();
      groups.computeIfAbsent(skuId, k -> new ArrayList<>()).add(row);
    }
    return groups;
  }

  private void confirmSkuChunk(
      String user, String importId, String skuId, List<TcgInventoryItem> rows) {
    var firstRow = rows.get(0);
    var skuSeed =
        TcgInventoryItem.createSku(
            user,
            skuId,
            firstRow.getScryfallId(),
            firstRow.getFinish(),
            firstRow.getCondition(),
            firstRow.getName(),
            firstRow.getSetCode(),
            firstRow.getSetName(),
            firstRow.getCollectorNumber(),
            firstRow.getFetchtcgCardId(),
            firstRow.getSuggestedPrice());
    skuSeed.setFetchtcgSetId(firstRow.getFetchtcgSetId());

    var units = new ArrayList<TcgInventoryItem>();
    for (var row : rows) {
      var unit =
          TcgInventoryItem.createUnit(
              user, skuId, row.getSequenceNumber(), "in_stock", importId, clock.now());
      if (row.getPhotos() != null && !row.getPhotos().isEmpty()) {
        unit.setPhotos(row.getPhotos());
      }
      units.add(unit);
    }

    tcgInventoryItemRepository.confirmImportSku(user, importId, skuSeed, units);
  }

  private List<PlacementInstruction> buildPlacementInstructions(List<TcgInventoryItem> keepRows) {
    var instructions = new ArrayList<PlacementInstruction>();

    int currentBlockNum = keepRows.get(0).getSequenceNumber() / 100;
    int blockStartIdx = 0;

    for (int i = 0; i < keepRows.size(); i++) {
      int seq = keepRows.get(i).getSequenceNumber();
      int blockNum = seq / 100;

      if (blockNum != currentBlockNum) {
        instructions.add(buildInstruction(keepRows, blockStartIdx, i - 1, currentBlockNum));
        currentBlockNum = blockNum;
        blockStartIdx = i;
      }
    }

    instructions.add(
        buildInstruction(keepRows, blockStartIdx, keepRows.size() - 1, currentBlockNum));

    return instructions;
  }

  private PlacementInstruction buildInstruction(
      List<TcgInventoryItem> rows, int startIdx, int endIdx, int blockNum) {
    var block = InventoryLocation.formatBlock(blockNum);
    var firstRow = rows.get(startIdx);
    var lastRow = rows.get(endIdx);
    int firstSeq = firstRow.getSequenceNumber();
    int lastSeq = lastRow.getSequenceNumber();

    return new PlacementInstruction(
        block,
        InventoryLocation.formatLocation(firstSeq),
        InventoryLocation.formatLocation(lastSeq),
        firstRow.getName(),
        lastRow.getName(),
        endIdx - startIdx + 1);
  }
}
