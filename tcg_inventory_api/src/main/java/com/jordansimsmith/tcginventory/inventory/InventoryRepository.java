package com.jordansimsmith.tcginventory.inventory;

import com.jordansimsmith.tcginventory.AuditItem;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

public class InventoryRepository {
  private final DynamoDbTable<UnitItem> unitTable;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;

  public InventoryRepository(
      DynamoDbTable<UnitItem> unitTable,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      UlidGenerator ulidGenerator) {
    this.unitTable = unitTable;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
    this.ulidGenerator = ulidGenerator;
  }

  public UnitItem getUnit(String user, String skuId, int sequenceNumber) {
    var key =
        Key.builder()
            .partitionValue(UnitItem.formatPk(user, skuId))
            .sortValue(UnitItem.formatSk(sequenceNumber))
            .build();
    return unitTable.getItem(request -> request.key(key).consistentRead(true));
  }

  public List<UnitItem> findUnitsToAllocate(String user, String skuId, int quantity) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(UnitItem.formatPk(user, skuId))
                        .sortValue(UnitItem.UNIT_PREFIX)
                        .build()))
            .scanIndexForward(true)
            .consistentRead(true)
            .build();

    return unitTable.query(request).stream()
        .flatMap(page -> page.items().stream())
        .filter(unit -> "in_stock".equals(unit.getStatus()))
        .limit(quantity)
        .toList();
  }

  public TransactWriteItem buildUnitReserveUpdate(
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
                        + "reservation_id"
                        + " = :orderId, "
                        + UnitItem.UPDATED_AT
                        + " = :now")
                .conditionExpression(
                    "#status = :inStock AND attribute_not_exists(" + UnitItem.RESERVATION_ID + ")")
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

  public void updateUnitForReservation(
      String user, String orderId, String skuId, int sequenceNumber) {
    var unit = getUnit(user, skuId, sequenceNumber);
    if (unit != null
        && "reserved".equals(unit.getStatus())
        && orderId.equals(unit.getReservationId())) {
      return;
    }

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                List.of(
                    buildUnitReserveUpdate(user, skuId, sequenceNumber, orderId),
                    buildSkuDirtyUpdate(user, skuId),
                    buildAuditPut(
                        user,
                        "reserve",
                        Map.of(
                            AuditItem.ORDER_ID, AttributeValue.builder().s(orderId).build(),
                            AuditItem.SKU_ID, AttributeValue.builder().s(skuId).build(),
                            AuditItem.SEQUENCE_NUMBER,
                                AttributeValue.builder().n(String.valueOf(sequenceNumber)).build(),
                            AuditItem.BEFORE_STATUS, AttributeValue.builder().s("in_stock").build(),
                            AuditItem.AFTER_STATUS,
                                AttributeValue.builder().s("reserved").build()))))
            .build());
  }

  public void updateUnitForSale(String user, String orderId, String skuId, int sequenceNumber) {
    var unit = getUnit(user, skuId, sequenceNumber);
    if (unit != null
        && "sold".equals(unit.getStatus())
        && orderId.equals(unit.getReservationId())) {
      return;
    }

    var sellUnit =
        TransactWriteItem.builder()
            .update(
                Update.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(
                        Map.of(
                            UnitItem.PK,
                            AttributeValue.builder().s(SkuItem.formatPk(user, skuId)).build(),
                            UnitItem.SK,
                            AttributeValue.builder().s(UnitItem.formatSk(sequenceNumber)).build()))
                    .updateExpression("SET #status = :sold, " + UnitItem.UPDATED_AT + " = :now")
                    .conditionExpression(
                        "#status = :reserved AND " + UnitItem.RESERVATION_ID + " = :orderId")
                    .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                    .expressionAttributeValues(
                        Map.of(
                            ":sold", AttributeValue.builder().s("sold").build(),
                            ":reserved", AttributeValue.builder().s("reserved").build(),
                            ":orderId", AttributeValue.builder().s(orderId).build(),
                            ":now",
                                AttributeValue.builder()
                                    .n(String.valueOf(clock.now().getEpochSecond()))
                                    .build()))
                    .build())
            .build();

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                sellUnit,
                buildSkuVersionBump(user, skuId),
                buildAuditPut(
                    user,
                    "sell",
                    Map.of(
                        AuditItem.ORDER_ID, AttributeValue.builder().s(orderId).build(),
                        AuditItem.SKU_ID, AttributeValue.builder().s(skuId).build(),
                        AuditItem.SEQUENCE_NUMBER,
                            AttributeValue.builder().n(String.valueOf(sequenceNumber)).build(),
                        AuditItem.BEFORE_STATUS, AttributeValue.builder().s("reserved").build(),
                        AuditItem.AFTER_STATUS, AttributeValue.builder().s("sold").build())))
            .build());
  }

  public void updateUnitForRelease(String user, String orderId, String skuId, int sequenceNumber) {
    var unit = getUnit(user, skuId, sequenceNumber);
    // a saved order allocation cannot be released if its unit no longer exists
    if (unit == null) {
      throw new IllegalStateException("unit is not reserved for this order");
    }
    // a prior delivery may have committed this release before losing its response
    if ("in_stock".equals(unit.getStatus()) && unit.getReservationId() == null) {
      return;
    }

    var releaseUnit =
        TransactWriteItem.builder()
            .update(
                Update.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(
                        Map.of(
                            UnitItem.PK,
                            AttributeValue.builder().s(UnitItem.formatPk(user, skuId)).build(),
                            UnitItem.SK,
                            AttributeValue.builder().s(UnitItem.formatSk(sequenceNumber)).build()))
                    .updateExpression(
                        "SET #status = :inStock, "
                            + UnitItem.UPDATED_AT
                            + " = :now REMOVE "
                            + UnitItem.RESERVATION_ID)
                    .conditionExpression(
                        "#status = :reserved AND " + UnitItem.RESERVATION_ID + " = :orderId")
                    .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                    .expressionAttributeValues(
                        Map.of(
                            ":inStock", AttributeValue.builder().s("in_stock").build(),
                            ":reserved", AttributeValue.builder().s("reserved").build(),
                            ":orderId", AttributeValue.builder().s(orderId).build(),
                            ":now",
                                AttributeValue.builder()
                                    .n(String.valueOf(clock.now().getEpochSecond()))
                                    .build()))
                    .build())
            .build();

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                releaseUnit,
                buildSkuDirtyUpdate(user, skuId),
                buildAuditPut(
                    user,
                    "release",
                    Map.of(
                        AuditItem.ORDER_ID, AttributeValue.builder().s(orderId).build(),
                        AuditItem.SKU_ID, AttributeValue.builder().s(skuId).build(),
                        AuditItem.SEQUENCE_NUMBER,
                            AttributeValue.builder().n(String.valueOf(sequenceNumber)).build(),
                        AuditItem.BEFORE_STATUS, AttributeValue.builder().s("reserved").build(),
                        AuditItem.AFTER_STATUS, AttributeValue.builder().s("in_stock").build())))
            .build());
  }

  public TransactWriteItem buildUnitDelete(String user, String skuId, int sequenceNumber) {
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

  public TransactWriteItem buildUnitPut(UnitItem unitItem) {
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(unitTable.tableSchema().itemToMap(unitItem, true))
                // preserve the existing unit if data already occupies this destination key.
                .conditionExpression("attribute_not_exists(pk)")
                .build())
        .build();
  }

  public TransactWriteItem buildSkuDirtyUpdate(String user, String skuId) {
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

  public TransactWriteItem buildSkuVersionBump(String user, String skuId) {
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
                .conditionExpression(
                    "attribute_exists(" + SkuItem.PK + ") AND attribute_exists(" + SkuItem.SK + ")")
                .expressionAttributeValues(Map.of(":one", AttributeValue.builder().n("1").build()))
                .build())
        .build();
  }

  private TransactWriteItem buildAuditPut(
      String user, String eventType, Map<String, AttributeValue> attributes) {
    var auditItem = new HashMap<>(attributes);
    auditItem.put(AuditItem.PK, AttributeValue.builder().s(AuditItem.formatPk(user)).build());
    auditItem.put(AuditItem.SK, AttributeValue.builder().s(ulidGenerator.generate()).build());
    auditItem.put(AuditItem.EVENT_TYPE, AttributeValue.builder().s(eventType).build());
    auditItem.put(
        AuditItem.CREATED_AT,
        AttributeValue.builder().n(String.valueOf(clock.now().getEpochSecond())).build());

    return TransactWriteItem.builder()
        .put(Put.builder().tableName(TcgInventoryTable.TABLE_NAME).item(auditItem).build())
        .build();
  }
}
