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
import com.jordansimsmith.ulid.UlidGenerator;
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
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class ConfirmImportHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfirmImportHandler.class);

  record ConfirmResponse(
      @JsonProperty("import_id") String importId,
      @JsonProperty("status") String status,
      @JsonProperty("unit_count") int unitCount,
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
  private final DynamoDbClient dynamoDbClient;
  private final UlidGenerator ulidGenerator;

  public ConfirmImportHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  ConfirmImportHandler(TcgInventoryFactory factory) {
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
    this.dynamoDbClient = factory.dynamoDbClient();
    this.ulidGenerator = factory.ulidGenerator();
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

    if ("review".equals(importItem.getStatus())) {
      importItem.setStatus("confirming");
      importItem.setUpdatedAt(clock.now());
      tcgInventoryTable.putItem(importItem);
    }

    var keepRows = queryKeepRows(user, importId);
    if (keepRows.isEmpty()) {
      importItem.setStatus("confirmed");
      importItem.setUpdatedAt(clock.now());
      tcgInventoryTable.putItem(importItem);
      return httpResponseFactory.ok(new ConfirmResponse(importId, "confirmed", 0, 0, 0, List.of()));
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
            importId, "confirmed", keepCount, firstSeq, lastSeq, placementInstructions));
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

    var response =
        dynamoDbClient.updateItem(
            UpdateItemRequest.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK,
                        AttributeValue.builder().s(TcgInventoryItem.formatUserPk(user)).build(),
                        TcgInventoryItem.SK,
                        AttributeValue.builder().s(TcgInventoryItem.formatCounterSk()).build()))
                .updateExpression("ADD " + TcgInventoryItem.NEXT_SEQUENCE_NUMBER + " :n")
                .expressionAttributeValues(
                    Map.of(":n", AttributeValue.builder().n(String.valueOf(keepCount)).build()))
                .returnValues("ALL_NEW")
                .build());

    int newValue =
        Integer.parseInt(response.attributes().get(TcgInventoryItem.NEXT_SEQUENCE_NUMBER).n());
    return newValue - keepCount;
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
    var transactItems = new ArrayList<TransactWriteItem>();

    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var firstRow = rows.get(0);

    for (var row : rows) {
      var unitItem = new HashMap<String, AttributeValue>();
      unitItem.put(TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build());
      unitItem.put(
          TcgInventoryItem.SK,
          AttributeValue.builder()
              .s(TcgInventoryItem.formatUnitSk(row.getSequenceNumber()))
              .build());
      unitItem.put(
          TcgInventoryItem.SEQUENCE_NUMBER,
          AttributeValue.builder().n(String.valueOf(row.getSequenceNumber())).build());
      unitItem.put(TcgInventoryItem.STATUS, AttributeValue.builder().s("in_stock").build());
      unitItem.put(TcgInventoryItem.IMPORT_ID, AttributeValue.builder().s(importId).build());
      unitItem.put(
          TcgInventoryItem.CREATED_AT,
          AttributeValue.builder().n(String.valueOf(clock.now().getEpochSecond())).build());

      transactItems.add(
          TransactWriteItem.builder()
              .put(
                  Put.builder()
                      .tableName(TcgInventoryItem.TABLE_NAME)
                      .item(unitItem)
                      .conditionExpression("attribute_not_exists(pk)")
                      .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.NONE)
                      .build())
              .build());
    }

    var skuUpdate =
        TransactWriteItem.builder()
            .update(
                Update.builder()
                    .tableName(TcgInventoryItem.TABLE_NAME)
                    .key(
                        Map.of(
                            TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                            TcgInventoryItem.SK,
                                AttributeValue.builder().s(TcgInventoryItem.formatSkuSk()).build()))
                    .updateExpression(
                        "ADD "
                            + TcgInventoryItem.VERSION
                            + " :one"
                            + " SET "
                            + TcgInventoryItem.SKU_ID
                            + " = :skuId, "
                            + TcgInventoryItem.SCRYFALL_ID
                            + " = :scryfallId, "
                            + "#finish = :finish, "
                            + "#condition = :condition, "
                            + "#name = :cardName, "
                            + TcgInventoryItem.SET_CODE
                            + " = :setCode, "
                            + TcgInventoryItem.SET_NAME
                            + " = :setName, "
                            + TcgInventoryItem.COLLECTOR_NUMBER
                            + " = :collectorNumber, "
                            + TcgInventoryItem.SUGGESTED_PRICE
                            + " = :suggestedPrice, "
                            + TcgInventoryItem.FETCHTCG_CARD_ID
                            + " = :fetchtcgCardId, "
                            + TcgInventoryItem.FETCHTCG_SET_ID
                            + " = :fetchtcgSetId, "
                            + TcgInventoryItem.DIRTY
                            + " = :dirty, "
                            + TcgInventoryItem.GSI1PK
                            + " = :gsi1pk, "
                            + TcgInventoryItem.GSI1SK
                            + " = :gsi1sk, "
                            + TcgInventoryItem.GSI2PK
                            + " = :gsi2pk, "
                            + TcgInventoryItem.GSI2SK
                            + " = :gsi2sk")
                    .expressionAttributeNames(
                        Map.of(
                            "#finish", TcgInventoryItem.FINISH,
                            "#condition", TcgInventoryItem.CONDITION,
                            "#name", TcgInventoryItem.NAME))
                    .expressionAttributeValues(
                        Map.ofEntries(
                            Map.entry(":one", AttributeValue.builder().n("1").build()),
                            Map.entry(":skuId", AttributeValue.builder().s(skuId).build()),
                            Map.entry(
                                ":scryfallId",
                                AttributeValue.builder().s(firstRow.getScryfallId()).build()),
                            Map.entry(
                                ":finish",
                                AttributeValue.builder().s(firstRow.getFinish()).build()),
                            Map.entry(
                                ":condition",
                                AttributeValue.builder().s(firstRow.getCondition()).build()),
                            Map.entry(
                                ":cardName",
                                AttributeValue.builder().s(firstRow.getName()).build()),
                            Map.entry(
                                ":setCode",
                                AttributeValue.builder().s(firstRow.getSetCode()).build()),
                            Map.entry(
                                ":setName",
                                AttributeValue.builder().s(firstRow.getSetName()).build()),
                            Map.entry(
                                ":collectorNumber",
                                AttributeValue.builder().s(firstRow.getCollectorNumber()).build()),
                            Map.entry(
                                ":suggestedPrice",
                                AttributeValue.builder().s(firstRow.getSuggestedPrice()).build()),
                            Map.entry(
                                ":fetchtcgCardId",
                                AttributeValue.builder().s(firstRow.getFetchtcgCardId()).build()),
                            Map.entry(
                                ":fetchtcgSetId",
                                AttributeValue.builder()
                                    .n(String.valueOf(firstRow.getFetchtcgSetId()))
                                    .build()),
                            Map.entry(":dirty", AttributeValue.builder().bool(true).build()),
                            Map.entry(
                                ":gsi1pk",
                                AttributeValue.builder()
                                    .s(TcgInventoryItem.formatGsi1pk(user))
                                    .build()),
                            Map.entry(
                                ":gsi1sk",
                                AttributeValue.builder()
                                    .s(TcgInventoryItem.formatGsi1sk(skuId))
                                    .build()),
                            Map.entry(
                                ":gsi2pk",
                                AttributeValue.builder()
                                    .s(TcgInventoryItem.formatGsi2pk(user))
                                    .build()),
                            Map.entry(
                                ":gsi2sk",
                                AttributeValue.builder()
                                    .s(
                                        TcgInventoryItem.formatGsi2sk(
                                            firstRow.getName().toLowerCase(), skuId))
                                    .build())))
                    .build())
            .build();
    transactItems.add(skuUpdate);

    var auditItem = new HashMap<String, AttributeValue>();
    auditItem.put(
        TcgInventoryItem.PK,
        AttributeValue.builder().s(TcgInventoryItem.formatAuditPk(user)).build());
    auditItem.put(
        TcgInventoryItem.SK, AttributeValue.builder().s(ulidGenerator.generate()).build());
    auditItem.put(
        TcgInventoryItem.EVENT_TYPE, AttributeValue.builder().s("import_confirm").build());
    auditItem.put(TcgInventoryItem.IMPORT_ID, AttributeValue.builder().s(importId).build());
    auditItem.put(TcgInventoryItem.SKU_ID, AttributeValue.builder().s(skuId).build());
    auditItem.put(
        TcgInventoryItem.CREATED_AT,
        AttributeValue.builder().n(String.valueOf(clock.now().getEpochSecond())).build());

    transactItems.add(
        TransactWriteItem.builder()
            .put(Put.builder().tableName(TcgInventoryItem.TABLE_NAME).item(auditItem).build())
            .build());

    try {
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder().transactItems(transactItems).build());
    } catch (TransactionCanceledException e) {
      LOGGER.info("transaction cancelled for SKU chunk {} (likely replay)", skuId);
    }
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
