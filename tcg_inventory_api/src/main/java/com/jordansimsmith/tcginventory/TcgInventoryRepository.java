package com.jordansimsmith.tcginventory;

import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class TcgInventoryRepository {
  private static final Logger LOGGER = LoggerFactory.getLogger(TcgInventoryRepository.class);

  private static final int MAX_TRANSACT_ITEMS = 100;

  public record SkuUnits(String skuId, List<Integer> sequenceNumbers) {}

  public record ScanPage(List<ScanItem> items, Map<String, AttributeValue> lastEvaluatedKey) {}

  private final DynamoDbTable<UnitItem> unitTable;
  private final DynamoDbTable<ScanItem> scanTable;
  private final DynamoDbTable<ScanRowItem> scanRowTable;
  private final DynamoDbTable<OrderItem> orderTable;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;

  public TcgInventoryRepository(
      DynamoDbTable<UnitItem> unitTable,
      DynamoDbTable<ScanItem> scanTable,
      DynamoDbTable<ScanRowItem> scanRowTable,
      DynamoDbTable<OrderItem> orderTable,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      UlidGenerator ulidGenerator) {
    this.unitTable = unitTable;
    this.scanTable = scanTable;
    this.scanRowTable = scanRowTable;
    this.orderTable = orderTable;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
    this.ulidGenerator = ulidGenerator;
  }

  public void createScan(ScanItem scanItem, List<ScanRowItem> rowItems) {
    for (int start = 0; start < rowItems.size(); start += MAX_TRANSACT_ITEMS) {
      var end = Math.min(start + MAX_TRANSACT_ITEMS, rowItems.size());
      var writes = rowItems.subList(start, end).stream().map(this::buildScanPut).toList();
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder().transactItems(writes).build());
    }

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder().transactItems(List.of(buildScanPut(scanItem))).build());
  }

  public ScanPage findScans(
      String user, int limit, @Nullable Map<String, AttributeValue> exclusiveStartKey) {
    var requestBuilder =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(SkuItem.formatUserPk(user))
                        .sortValue(ScanItem.SCAN_PREFIX)
                        .build()))
            .scanIndexForward(false)
            .limit(limit);
    if (exclusiveStartKey != null && !exclusiveStartKey.isEmpty()) {
      requestBuilder.exclusiveStartKey(exclusiveStartKey);
    }

    var page = scanTable.query(requestBuilder.build()).stream().findFirst().orElse(null);
    if (page == null) {
      return new ScanPage(List.of(), Map.of());
    }
    return new ScanPage(page.items(), page.lastEvaluatedKey());
  }

  public boolean transitionScanToIdentifying(String user, String scanId) {
    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryTable.TABLE_NAME)
              .key(
                  Map.of(
                      SkuItem.PK, AttributeValue.builder().s(SkuItem.formatUserPk(user)).build(),
                      SkuItem.SK, AttributeValue.builder().s(ScanItem.formatSk(scanId)).build()))
              .updateExpression("SET #status = :identifying, " + UnitItem.UPDATED_AT + " = :now")
              .conditionExpression("attribute_exists(" + SkuItem.PK + ") AND #status = :uploading")
              .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
              .expressionAttributeValues(
                  Map.of(
                      ":identifying", AttributeValue.builder().s("identifying").build(),
                      ":uploading", AttributeValue.builder().s("uploading").build(),
                      ":now",
                          AttributeValue.builder()
                              .n(String.valueOf(clock.now().getEpochSecond()))
                              .build()))
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  public boolean transitionScanToConfirmed(String user, String scanId, String importId) {
    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryTable.TABLE_NAME)
              .key(
                  Map.of(
                      SkuItem.PK, AttributeValue.builder().s(SkuItem.formatUserPk(user)).build(),
                      SkuItem.SK, AttributeValue.builder().s(ScanItem.formatSk(scanId)).build()))
              .updateExpression(
                  "SET #status = :confirmed, "
                      + ImportItem.IMPORT_ID
                      + " = :importId, "
                      + UnitItem.UPDATED_AT
                      + " = :now")
              .conditionExpression("attribute_exists(" + SkuItem.PK + ") AND #status = :reviewing")
              .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
              .expressionAttributeValues(
                  Map.of(
                      ":confirmed", AttributeValue.builder().s("confirmed").build(),
                      ":reviewing", AttributeValue.builder().s("reviewing").build(),
                      ":importId", AttributeValue.builder().s(importId).build(),
                      ":now",
                          AttributeValue.builder()
                              .n(String.valueOf(clock.now().getEpochSecond()))
                              .build()))
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  public boolean deleteScanRow(String user, String scanId, int scanPosition) {
    var scanKey =
        Map.of(
            SkuItem.PK,
            AttributeValue.builder().s(SkuItem.formatUserPk(user)).build(),
            SkuItem.SK,
            AttributeValue.builder().s(ScanItem.formatSk(scanId)).build());
    var rowKey =
        Map.of(
            SkuItem.PK,
            AttributeValue.builder().s(ScanRowItem.formatPk(user, scanId)).build(),
            SkuItem.SK,
            AttributeValue.builder().s(ScanRowItem.formatSk(scanPosition)).build());

    try {
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder()
              .transactItems(
                  List.of(
                      TransactWriteItem.builder()
                          .conditionCheck(
                              ConditionCheck.builder()
                                  .tableName(TcgInventoryTable.TABLE_NAME)
                                  .key(scanKey)
                                  .conditionExpression(
                                      "attribute_exists("
                                          + SkuItem.PK
                                          + ") AND #status = :reviewing")
                                  .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                                  .expressionAttributeValues(
                                      Map.of(
                                          ":reviewing",
                                          AttributeValue.builder().s("reviewing").build()))
                                  .build())
                          .build(),
                      TransactWriteItem.builder()
                          .delete(
                              Delete.builder()
                                  .tableName(TcgInventoryTable.TABLE_NAME)
                                  .key(rowKey)
                                  .conditionExpression("attribute_exists(" + SkuItem.PK + ")")
                                  .build())
                          .build()))
              .build());
      return true;
    } catch (TransactionCanceledException e) {
      return false;
    }
  }

  public boolean deleteScan(String user, String scanId) {
    try {
      dynamoDbClient.deleteItem(
          DeleteItemRequest.builder()
              .tableName(TcgInventoryTable.TABLE_NAME)
              .key(
                  Map.of(
                      SkuItem.PK,
                      AttributeValue.builder().s(SkuItem.formatUserPk(user)).build(),
                      SkuItem.SK,
                      AttributeValue.builder().s(ScanItem.formatSk(scanId)).build()))
              .conditionExpression(
                  "attribute_exists("
                      + SkuItem.PK
                      + ") AND #status IN (:uploading, "
                      + ":identifying, :reviewing)")
              .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
              .expressionAttributeValues(
                  Map.of(
                      ":uploading", AttributeValue.builder().s("uploading").build(),
                      ":identifying", AttributeValue.builder().s("identifying").build(),
                      ":reviewing", AttributeValue.builder().s("reviewing").build()))
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  public void deleteScanRows(List<ScanRowItem> rowItems) {
    executeChunked(rowItems.stream().map(this::buildScanRowDelete).toList());
  }

  public List<ScanRowItem> findScanRows(String user, String scanId) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(ScanRowItem.formatPk(user, scanId))
                        .sortValue(ImportRowItem.ROW_PREFIX)
                        .build()))
            .scanIndexForward(true)
            .build();
    return scanRowTable.query(request).stream().flatMap(page -> page.items().stream()).toList();
  }

  @Nullable
  public ScanRowItem getScanRow(String user, String scanId, int scanPosition) {
    return scanRowTable.getItem(
        Key.builder()
            .partitionValue(ScanRowItem.formatPk(user, scanId))
            .sortValue(ScanRowItem.formatSk(scanPosition))
            .build());
  }

  @Nullable
  public ScanItem getScan(String user, String scanId) {
    return scanTable.getItem(
        Key.builder()
            .partitionValue(SkuItem.formatUserPk(user))
            .sortValue(ScanItem.formatSk(scanId))
            .build());
  }

  public List<UnitItem> findUnits(String user, String skuId) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(SkuItem.formatPk(user, skuId))
                        .sortValue(UnitItem.UNIT_PREFIX)
                        .build()))
            .build();

    return unitTable.query(request).stream().flatMap(page -> page.items().stream()).toList();
  }

  public List<UnitItem> findUnitsToAllocate(
      String user, String skuId, String orderId, int quantity) {
    var results = new ArrayList<UnitItem>();
    for (var item : findUnits(user, skuId)) {
      // units already reserved for this order were allocated by a run that died before
      // writing the order item; reclaiming them keeps retries convergent
      var reclaimed = "reserved".equals(item.getStatus()) && orderId.equals(item.getOrderId());
      if ("in_stock".equals(item.getStatus()) || reclaimed) {
        results.add(item);
        if (results.size() >= quantity) {
          break;
        }
      }
    }
    return results;
  }

  // the conditional order put and the audit entry ride in the final chunk, so the order item's
  // existence marks the whole reservation complete; a run that dies earlier leaves the offer
  // untracked and the next run reclaims the units it already reserved
  public void reserveOrder(String user, OrderItem orderItem, List<SkuUnits> newReservations) {
    var transactItems = new ArrayList<TransactWriteItem>();
    for (var reservation : newReservations) {
      transactItems.add(buildSkuDirtyUpdate(user, reservation.skuId()));
      for (var sequenceNumber : reservation.sequenceNumbers()) {
        transactItems.add(
            buildUnitReserveUpdate(
                user, reservation.skuId(), sequenceNumber, orderItem.getOrderId()));
      }
    }

    transactItems.add(
        TransactWriteItem.builder()
            .put(
                Put.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .item(orderTable.tableSchema().itemToMap(orderItem, true))
                    .conditionExpression("attribute_not_exists(pk)")
                    .build())
            .build());
    transactItems.add(
        buildAuditPut(
            user,
            "reserve",
            Map.of(
                OrderItem.ORDER_ID, AttributeValue.builder().s(orderItem.getOrderId()).build())));

    executeChunked(transactItems);
  }

  // the status flip and its payment audit land atomically so report staleness never misses a
  // revenue-affecting advance; replayed slices re-read orders and skip those already advanced,
  // so a condition failure is a real conflict and fails loudly
  public void advanceOrderToPickReady(
      String user, String orderId, String fetchtcgStatus, String fetchtcgCurrentAction) {
    executeChunked(
        List.of(
            buildOrderPickReadyUpdate(user, orderId, fetchtcgStatus, fetchtcgCurrentAction),
            buildAuditPut(
                user,
                "payment",
                Map.of(OrderItem.ORDER_ID, AttributeValue.builder().s(orderId).build()))));
  }

  // fulfillment details mirror the offer and move neither inventory nor revenue, so this is a
  // plain update with no audit entry: auditing it would mark the report stale for nothing
  public void updateOrderFulfillment(
      String user,
      String orderId,
      @Nullable String buyerName,
      @Nullable OrderItem.BuyerAddress buyerAddress,
      @Nullable String postageOption) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(TcgInventoryTable.TABLE_NAME)
            .key(
                Map.of(
                    SkuItem.PK,
                    AttributeValue.builder().s(SkuItem.formatUserPk(user)).build(),
                    SkuItem.SK,
                    AttributeValue.builder().s(OrderItem.formatSk(orderId)).build()))
            .updateExpression(
                "SET "
                    + OrderItem.BUYER_NAME
                    + " = :buyerName, "
                    + OrderItem.BUYER_ADDRESS
                    + " = :buyerAddress, "
                    + OrderItem.POSTAGE_OPTION
                    + " = :postageOption, "
                    + UnitItem.UPDATED_AT
                    + " = :now")
            .conditionExpression("attribute_exists(" + SkuItem.PK + ")")
            .expressionAttributeValues(
                Map.of(
                    ":buyerName", toAttributeValue(buyerName),
                    ":buyerAddress", toAttributeValue(buyerAddress),
                    ":postageOption", toAttributeValue(postageOption),
                    ":now",
                        AttributeValue.builder()
                            .n(String.valueOf(clock.now().getEpochSecond()))
                            .build()))
            .build());
  }

  private static AttributeValue toAttributeValue(@Nullable String value) {
    return value == null
        ? AttributeValue.builder().nul(true).build()
        : AttributeValue.builder().s(value).build();
  }

  private static AttributeValue toAttributeValue(@Nullable OrderItem.BuyerAddress address) {
    if (address == null) {
      return AttributeValue.builder().nul(true).build();
    }

    var parts = new HashMap<String, AttributeValue>();
    putAddressPart(parts, OrderItem.BuyerAddress.LINE1, address.getLine1());
    putAddressPart(parts, OrderItem.BuyerAddress.LINE2, address.getLine2());
    putAddressPart(parts, OrderItem.BuyerAddress.SUBURB, address.getSuburb());
    putAddressPart(parts, OrderItem.BuyerAddress.CITY, address.getCity());
    putAddressPart(parts, OrderItem.BuyerAddress.POST_CODE, address.getPostCode());
    putAddressPart(parts, OrderItem.BuyerAddress.COUNTRY, address.getCountry());
    return AttributeValue.builder().m(parts).build();
  }

  private static void putAddressPart(
      Map<String, AttributeValue> parts, String attribute, @Nullable String value) {
    if (value != null) {
      parts.put(attribute, AttributeValue.builder().s(value).build());
    }
  }

  // the conditional order flip and the audit entry ride in the final chunk, so a partially applied
  // release leaves the order awaiting_payment and the next run finishes it; the unit condition
  // tolerates units the failed attempt already released
  public void releaseOrder(
      String user, String orderId, String fetchtcgStatus, List<SkuUnits> releases) {
    var transactItems = new ArrayList<TransactWriteItem>();
    for (var release : releases) {
      transactItems.add(buildSkuDirtyUpdate(user, release.skuId()));
      for (var sequenceNumber : release.sequenceNumbers()) {
        transactItems.add(buildUnitReleaseUpdate(user, release.skuId(), sequenceNumber));
      }
    }

    transactItems.add(buildOrderVoidedUpdate(user, orderId, fetchtcgStatus));
    transactItems.add(
        buildAuditPut(
            user,
            "release",
            Map.of(OrderItem.ORDER_ID, AttributeValue.builder().s(orderId).build())));

    executeChunked(transactItems);
  }

  // the conditional order flip and the audit entry ride in the final chunk; a partially applied
  // confirm leaves the order to_pick so the client can retry, and the unit condition tolerates
  // units the failed attempt already sold
  public void sellOrder(String user, String orderId, List<SkuUnits> sales) {
    var transactItems = new ArrayList<TransactWriteItem>();
    for (var sale : sales) {
      transactItems.add(buildSkuVersionBump(user, sale.skuId()));
      for (var sequenceNumber : sale.sequenceNumbers()) {
        transactItems.add(buildUnitSellUpdate(user, sale.skuId(), sequenceNumber));
      }
    }

    transactItems.add(buildOrderFulfilledUpdate(user, orderId));
    transactItems.add(
        buildAuditPut(
            user, "sell", Map.of(OrderItem.ORDER_ID, AttributeValue.builder().s(orderId).build())));

    executeChunked(transactItems);
  }

  public void removeUnit(String user, String skuId, int sequenceNumber, @Nullable String reason) {
    var auditAttributes = new HashMap<String, AttributeValue>();
    auditAttributes.put(SkuItem.SKU_ID, AttributeValue.builder().s(skuId).build());
    auditAttributes.put(
        UnitItem.SEQUENCE_NUMBER,
        AttributeValue.builder().n(String.valueOf(sequenceNumber)).build());
    if (reason != null && !reason.isEmpty()) {
      auditAttributes.put(
          ImportRowItem.DECISION_REASON, AttributeValue.builder().s(reason).build());
    }

    executeChunked(
        List.of(
            buildSkuDirtyUpdate(user, skuId),
            buildUnitRemoveUpdate(user, skuId, sequenceNumber),
            buildAuditPut(user, "adjustment", auditAttributes)));
  }

  // one transaction across both SKU partitions: the unit moves keeping its sequence number and
  // photos, the source SKU is dirtied, and the target SKU record is created or refreshed
  public String updateUnitCondition(
      String user, SkuItem skuItem, UnitItem unitItem, String condition) {
    var targetSkuId = skuItem.getScryfallId() + "#" + skuItem.getFinish() + "#" + condition;
    var targetSku =
        SkuItem.create(
            user,
            targetSkuId,
            skuItem.getScryfallId(),
            skuItem.getFinish(),
            condition,
            skuItem.getName(),
            skuItem.getSetCode(),
            skuItem.getSetName(),
            skuItem.getCollectorNumber(),
            skuItem.getFetchtcgCardId(),
            skuItem.getSuggestedPrice());

    var movedUnit =
        UnitItem.create(
            user,
            targetSkuId,
            unitItem.getSequenceNumber(),
            "in_stock",
            unitItem.getImportId(),
            unitItem.getCreatedAt());
    if (unitItem.getPhotos() != null && !unitItem.getPhotos().isEmpty()) {
      movedUnit.setPhotos(unitItem.getPhotos());
    }

    executeChunked(
        List.of(
            buildUnitDelete(user, skuItem.getSkuId(), unitItem.getSequenceNumber()),
            buildUnitPut(movedUnit),
            buildSkuDirtyUpdate(user, skuItem.getSkuId()),
            buildSkuUpsert(targetSku),
            buildAuditPut(
                user,
                "adjustment",
                Map.of(
                    SkuItem.SKU_ID,
                    AttributeValue.builder().s(skuItem.getSkuId()).build(),
                    UnitItem.SEQUENCE_NUMBER,
                    AttributeValue.builder()
                        .n(String.valueOf(unitItem.getSequenceNumber()))
                        .build()))));

    return targetSkuId;
  }

  public int allocateSequenceRange(String user, int count) {
    var response =
        dynamoDbClient.updateItem(
            UpdateItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK,
                        AttributeValue.builder().s(SkuItem.formatUserPk(user)).build(),
                        SkuItem.SK,
                        AttributeValue.builder().s(SequenceCounterItem.formatSk()).build()))
                .updateExpression("ADD " + SequenceCounterItem.NEXT_SEQUENCE_NUMBER + " :n")
                .expressionAttributeValues(
                    Map.of(":n", AttributeValue.builder().n(String.valueOf(count)).build()))
                .returnValues("ALL_NEW")
                .build());

    int newValue =
        Integer.parseInt(response.attributes().get(SequenceCounterItem.NEXT_SEQUENCE_NUMBER).n());
    return newValue - count;
  }

  // deliberately a single transaction rather than a chunked sequence: a replayed chunk fails its
  // unit-exists condition and the whole transaction cancels atomically into a no-op
  public void confirmImportSku(
      String user, String importId, SkuItem skuSeed, List<UnitItem> units) {
    var transactItems = new ArrayList<TransactWriteItem>();
    for (var unit : units) {
      transactItems.add(
          TransactWriteItem.builder()
              .put(
                  Put.builder()
                      .tableName(TcgInventoryTable.TABLE_NAME)
                      .item(unitTable.tableSchema().itemToMap(unit, true))
                      .conditionExpression("attribute_not_exists(pk)")
                      .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.NONE)
                      .build())
              .build());
    }
    transactItems.add(buildSkuUpsert(skuSeed));
    transactItems.add(
        buildAuditPut(
            user,
            "import_confirm",
            Map.of(
                ImportItem.IMPORT_ID,
                AttributeValue.builder().s(importId).build(),
                SkuItem.SKU_ID,
                AttributeValue.builder().s(skuSeed.getSkuId()).build())));

    try {
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder().transactItems(transactItems).build());
    } catch (TransactionCanceledException e) {
      LOGGER.info("transaction cancelled for SKU chunk {} (likely replay)", skuSeed.getSkuId());
    }
  }

  private void executeChunked(List<TransactWriteItem> transactItems) {
    for (int start = 0; start < transactItems.size(); start += MAX_TRANSACT_ITEMS) {
      var chunk =
          transactItems.subList(start, Math.min(start + MAX_TRANSACT_ITEMS, transactItems.size()));
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder().transactItems(chunk).build());
    }
  }

  private TransactWriteItem buildScanPut(ScanItem item) {
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(scanTable.tableSchema().itemToMap(item, true))
                .conditionExpression("attribute_not_exists(" + SkuItem.PK + ")")
                .build())
        .build();
  }

  private TransactWriteItem buildScanPut(ScanRowItem item) {
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(scanRowTable.tableSchema().itemToMap(item, true))
                .conditionExpression("attribute_not_exists(" + SkuItem.PK + ")")
                .build())
        .build();
  }

  private TransactWriteItem buildScanRowDelete(ScanRowItem item) {
    return TransactWriteItem.builder()
        .delete(
            Delete.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK,
                        AttributeValue.builder().s(item.getPk()).build(),
                        SkuItem.SK,
                        AttributeValue.builder().s(item.getSk()).build()))
                .build())
        .build();
  }

  private TransactWriteItem buildUnitReserveUpdate(
      String user, String skuId, int sequenceNumber, String orderId) {
    var skuPk = SkuItem.formatPk(user, skuId);
    var unitSk = UnitItem.formatSk(sequenceNumber);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(unitSk).build()))
                .updateExpression(
                    "SET #status = :reserved, "
                        + OrderItem.ORDER_ID
                        + " = :orderId, "
                        + UnitItem.UPDATED_AT
                        + " = :now")
                .conditionExpression("#status = :inStock")
                .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":reserved", AttributeValue.builder().s("reserved").build(),
                        ":inStock", AttributeValue.builder().s("in_stock").build(),
                        ":orderId", AttributeValue.builder().s(orderId).build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  private TransactWriteItem buildUnitSellUpdate(String user, String skuId, int sequenceNumber) {
    var skuPk = SkuItem.formatPk(user, skuId);
    var unitSk = UnitItem.formatSk(sequenceNumber);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(unitSk).build()))
                .updateExpression("SET #status = :sold, " + UnitItem.UPDATED_AT + " = :now")
                .conditionExpression("#status IN (:reserved, :sold)")
                .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":sold", AttributeValue.builder().s("sold").build(),
                        ":reserved", AttributeValue.builder().s("reserved").build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  private TransactWriteItem buildUnitReleaseUpdate(String user, String skuId, int sequenceNumber) {
    var skuPk = SkuItem.formatPk(user, skuId);
    var unitSk = UnitItem.formatSk(sequenceNumber);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(unitSk).build()))
                .updateExpression(
                    "SET #status = :inStock, "
                        + UnitItem.UPDATED_AT
                        + " = :now REMOVE "
                        + OrderItem.ORDER_ID)
                .conditionExpression("#status IN (:reserved, :inStock)")
                .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":inStock", AttributeValue.builder().s("in_stock").build(),
                        ":reserved", AttributeValue.builder().s("reserved").build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  private TransactWriteItem buildUnitRemoveUpdate(String user, String skuId, int sequenceNumber) {
    var skuPk = SkuItem.formatPk(user, skuId);
    var unitSk = UnitItem.formatSk(sequenceNumber);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(unitSk).build()))
                .updateExpression("SET #status = :removed, " + UnitItem.UPDATED_AT + " = :now")
                .conditionExpression("#status = :inStock")
                .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":removed", AttributeValue.builder().s("removed").build(),
                        ":inStock", AttributeValue.builder().s("in_stock").build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  private TransactWriteItem buildUnitDelete(String user, String skuId, int sequenceNumber) {
    var skuPk = SkuItem.formatPk(user, skuId);
    var unitSk = UnitItem.formatSk(sequenceNumber);

    return TransactWriteItem.builder()
        .delete(
            Delete.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(unitSk).build()))
                .conditionExpression("#status = :inStock")
                .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                .expressionAttributeValues(
                    Map.of(":inStock", AttributeValue.builder().s("in_stock").build()))
                .build())
        .build();
  }

  private TransactWriteItem buildUnitPut(UnitItem unitItem) {
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(unitTable.tableSchema().itemToMap(unitItem, true))
                .build())
        .build();
  }

  private TransactWriteItem buildSkuUpsert(SkuItem skuSeed) {
    var expression =
        new StringBuilder(
            "ADD "
                + SkuItem.VERSION
                + " :one SET "
                + SkuItem.SKU_ID
                + " = :skuId, "
                + SkuItem.SCRYFALL_ID
                + " = :scryfallId, "
                + "#finish = :finish, "
                + "#condition = :condition, "
                + "#name = :cardName, "
                + SkuItem.SET_CODE
                + " = :setCode, "
                + SkuItem.SET_NAME
                + " = :setName, "
                + SkuItem.COLLECTOR_NUMBER
                + " = :collectorNumber, "
                + SkuItem.DIRTY
                + " = :dirty, "
                + SkuItem.GSI1PK
                + " = :gsi1pk, "
                + SkuItem.GSI1SK
                + " = :gsi1sk, "
                + SkuItem.GSI2PK
                + " = :gsi2pk, "
                + SkuItem.GSI2SK
                + " = :gsi2sk");

    var values = new HashMap<String, AttributeValue>();
    values.put(":one", AttributeValue.builder().n("1").build());
    values.put(":skuId", AttributeValue.builder().s(skuSeed.getSkuId()).build());
    values.put(":scryfallId", AttributeValue.builder().s(skuSeed.getScryfallId()).build());
    values.put(":finish", AttributeValue.builder().s(skuSeed.getFinish()).build());
    values.put(":condition", AttributeValue.builder().s(skuSeed.getCondition()).build());
    values.put(":cardName", AttributeValue.builder().s(skuSeed.getName()).build());
    values.put(":setCode", AttributeValue.builder().s(skuSeed.getSetCode()).build());
    values.put(":setName", AttributeValue.builder().s(skuSeed.getSetName()).build());
    values.put(
        ":collectorNumber", AttributeValue.builder().s(skuSeed.getCollectorNumber()).build());
    values.put(":dirty", AttributeValue.builder().bool(true).build());
    values.put(":gsi1pk", AttributeValue.builder().s(skuSeed.getGsi1pk()).build());
    values.put(":gsi1sk", AttributeValue.builder().s(skuSeed.getGsi1sk()).build());
    values.put(":gsi2pk", AttributeValue.builder().s(skuSeed.getGsi2pk()).build());
    values.put(":gsi2sk", AttributeValue.builder().s(skuSeed.getGsi2sk()).build());

    if (skuSeed.getSuggestedPrice() != null) {
      expression.append(", " + SkuItem.SUGGESTED_PRICE + " = :suggestedPrice");
      values.put(
          ":suggestedPrice", AttributeValue.builder().s(skuSeed.getSuggestedPrice()).build());
    }
    if (skuSeed.getFetchtcgCardId() != null) {
      expression.append(", " + SkuItem.FETCHTCG_CARD_ID + " = :fetchtcgCardId");
      values.put(
          ":fetchtcgCardId", AttributeValue.builder().s(skuSeed.getFetchtcgCardId()).build());
    }
    if (skuSeed.getFetchtcgSetId() != null) {
      expression.append(", " + SkuItem.FETCHTCG_SET_ID + " = :fetchtcgSetId");
      values.put(
          ":fetchtcgSetId",
          AttributeValue.builder().n(String.valueOf(skuSeed.getFetchtcgSetId())).build());
    }

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuSeed.getPk()).build(),
                        SkuItem.SK, AttributeValue.builder().s(skuSeed.getSk()).build()))
                .updateExpression(expression.toString())
                .expressionAttributeNames(
                    Map.of(
                        "#finish", SkuItem.FINISH,
                        "#condition", SkuItem.CONDITION,
                        "#name", SkuItem.NAME))
                .expressionAttributeValues(values)
                .build())
        .build();
  }

  private TransactWriteItem buildSkuDirtyUpdate(String user, String skuId) {
    var skuPk = SkuItem.formatPk(user, skuId);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(SkuItem.formatSk()).build()))
                .updateExpression(
                    "ADD "
                        + SkuItem.VERSION
                        + " :one SET "
                        + SkuItem.DIRTY
                        + " = :dirty, "
                        + SkuItem.GSI1PK
                        + " = :gsi1pk")
                .expressionAttributeValues(
                    Map.of(
                        ":one", AttributeValue.builder().n("1").build(),
                        ":dirty", AttributeValue.builder().bool(true).build(),
                        ":gsi1pk", AttributeValue.builder().s(SkuItem.formatGsi1pk(user)).build()))
                .build())
        .build();
  }

  private TransactWriteItem buildSkuVersionBump(String user, String skuId) {
    var skuPk = SkuItem.formatPk(user, skuId);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(skuPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(SkuItem.formatSk()).build()))
                .updateExpression("ADD " + SkuItem.VERSION + " :one")
                .expressionAttributeValues(Map.of(":one", AttributeValue.builder().n("1").build()))
                .build())
        .build();
  }

  private TransactWriteItem buildOrderPickReadyUpdate(
      String user, String orderId, String fetchtcgStatus, String fetchtcgCurrentAction) {
    var userPk = SkuItem.formatUserPk(user);
    var orderSk = OrderItem.formatSk(orderId);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(userPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(orderSk).build()))
                .updateExpression(
                    "SET #status = :toPick, "
                        + OrderItem.FETCHTCG_STATUS
                        + " = :fetchtcgStatus, "
                        + OrderItem.FETCHTCG_CURRENT_ACTION
                        + " = :fetchtcgCurrentAction, "
                        + UnitItem.UPDATED_AT
                        + " = :now")
                .conditionExpression("#status = :awaitingPayment")
                .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":toPick", AttributeValue.builder().s("to_pick").build(),
                        ":awaitingPayment", AttributeValue.builder().s("awaiting_payment").build(),
                        ":fetchtcgStatus", AttributeValue.builder().s(fetchtcgStatus).build(),
                        ":fetchtcgCurrentAction",
                            AttributeValue.builder().s(fetchtcgCurrentAction).build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  // a cancelled offer carries no currentAction, so the stale one is dropped rather than kept
  private TransactWriteItem buildOrderVoidedUpdate(
      String user, String orderId, String fetchtcgStatus) {
    var userPk = SkuItem.formatUserPk(user);
    var orderSk = OrderItem.formatSk(orderId);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(userPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(orderSk).build()))
                .updateExpression(
                    "SET #status = :voided, "
                        + OrderItem.FETCHTCG_STATUS
                        + " = :fetchtcgStatus, "
                        + UnitItem.UPDATED_AT
                        + " = :now REMOVE "
                        + OrderItem.FETCHTCG_CURRENT_ACTION)
                .conditionExpression("#status = :awaitingPayment")
                .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":voided", AttributeValue.builder().s("voided").build(),
                        ":awaitingPayment", AttributeValue.builder().s("awaiting_payment").build(),
                        ":fetchtcgStatus", AttributeValue.builder().s(fetchtcgStatus).build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  private TransactWriteItem buildOrderFulfilledUpdate(String user, String orderId) {
    var userPk = SkuItem.formatUserPk(user);
    var orderSk = OrderItem.formatSk(orderId);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        SkuItem.PK, AttributeValue.builder().s(userPk).build(),
                        SkuItem.SK, AttributeValue.builder().s(orderSk).build()))
                .updateExpression("SET #status = :fulfilled, " + UnitItem.UPDATED_AT + " = :now")
                .conditionExpression("#status = :toPick")
                .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":fulfilled", AttributeValue.builder().s("fulfilled").build(),
                        ":toPick", AttributeValue.builder().s("to_pick").build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  private TransactWriteItem buildAuditPut(
      String user, String eventType, Map<String, AttributeValue> attributes) {
    var auditItem = new HashMap<>(attributes);
    auditItem.put(SkuItem.PK, AttributeValue.builder().s(AuditItem.formatPk(user)).build());
    auditItem.put(SkuItem.SK, AttributeValue.builder().s(ulidGenerator.generate()).build());
    auditItem.put(AuditItem.EVENT_TYPE, AttributeValue.builder().s(eventType).build());
    auditItem.put(
        UnitItem.CREATED_AT,
        AttributeValue.builder().n(String.valueOf(clock.now().getEpochSecond())).build());

    return TransactWriteItem.builder()
        .put(Put.builder().tableName(TcgInventoryTable.TABLE_NAME).item(auditItem).build())
        .build();
  }
}
