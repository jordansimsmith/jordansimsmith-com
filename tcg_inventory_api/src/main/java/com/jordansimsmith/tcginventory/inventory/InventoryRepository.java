package com.jordansimsmith.tcginventory.inventory;

import com.jordansimsmith.tcginventory.TcgInventoryTable;
import java.util.Map;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.Update;

public class InventoryRepository {
  private final DynamoDbTable<UnitItem> unitTable;

  public InventoryRepository(DynamoDbTable<UnitItem> unitTable) {
    this.unitTable = unitTable;
  }

  public UnitItem getUnit(String user, String skuId, int sequenceNumber) {
    var key =
        Key.builder()
            .partitionValue(UnitItem.formatPk(user, skuId))
            .sortValue(UnitItem.formatSk(sequenceNumber))
            .build();
    return unitTable.getItem(request -> request.key(key).consistentRead(true));
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
}
