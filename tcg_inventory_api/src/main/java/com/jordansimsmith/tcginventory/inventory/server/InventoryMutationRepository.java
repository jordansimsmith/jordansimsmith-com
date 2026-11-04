package com.jordansimsmith.tcginventory.inventory.server;

import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

public class InventoryMutationRepository {
  private final DynamoDbClient dynamoDbClient;
  private final DynamoDbTable<InventoryCreateUnitOperationItem> createUnitOperationTable;
  private final DynamoDbTable<InventoryReserveUnitOperationItem> reserveUnitOperationTable;
  private final DynamoDbTable<InventoryReservationItem> reservationTable;
  private final String tableName;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;

  public InventoryMutationRepository(
      DynamoDbClient dynamoDbClient,
      DynamoDbTable<InventoryCreateUnitOperationItem> createUnitOperationTable,
      DynamoDbTable<InventoryReserveUnitOperationItem> reserveUnitOperationTable,
      DynamoDbTable<InventoryReservationItem> reservationTable,
      String tableName,
      Clock clock,
      UlidGenerator ulidGenerator) {
    this.dynamoDbClient = dynamoDbClient;
    this.createUnitOperationTable = createUnitOperationTable;
    this.reserveUnitOperationTable = reserveUnitOperationTable;
    this.reservationTable = reservationTable;
    this.tableName = tableName;
    this.clock = clock;
    this.ulidGenerator = ulidGenerator;
  }

  @Nullable
  public InventoryCreateUnitOperationItem getCreateUnitOperation(String user, String operationId) {
    var key =
        Key.builder()
            .partitionValue("USER#" + user + "#INVENTORY")
            .sortValue("OPERATION#CREATE_UNIT#" + operationId)
            .build();

    return createUnitOperationTable.getItem(request -> request.key(key).consistentRead(true));
  }

  public InventoryCreateUnitOperationItem createUnit(
      String user,
      String operationId,
      InventorySkuItem sku,
      List<String> photoIds,
      boolean newSku) {
    var skuId = sku.getSkuId();

    var allocated =
        dynamoDbClient.updateItem(
            r ->
                r.tableName(tableName)
                    .key(
                        Map.of(
                            "pk",
                            AttributeValue.builder().s("USER#" + user).build(),
                            "sk",
                            AttributeValue.builder()
                                .s("COUNTER#SEQUENCE#" + sku.getGame())
                                .build()))
                    .updateExpression("SET game = :game ADD next_sequence_number :one")
                    .expressionAttributeValues(
                        Map.of(
                            ":game",
                            AttributeValue.builder().s(sku.getGame()).build(),
                            ":one",
                            AttributeValue.builder().n("1").build()))
                    .returnValues(ReturnValue.ALL_NEW));
    var sequence = Integer.parseInt(allocated.attributes().get("next_sequence_number").n()) - 1;

    var now = clock.now();
    var unit = new InventoryUnitItem();
    unit.setPk(InventoryUnitItem.formatPk(user, skuId));
    unit.setSk(InventoryUnitItem.formatSk(sequence));
    unit.setSequenceNumber(sequence);
    unit.setGame(sku.getGame());
    unit.setGsi3pk("USER#" + user + "#UNITS#" + sku.getGame());
    unit.setStatus("in_stock");
    unit.setCreatedAt(now);
    unit.setPhotos(
        photoIds.stream()
            .map(
                id -> {
                  var photo = new InventoryUnitItem.PhotoItem();
                  photo.setPhotoId(id);
                  return photo;
                })
            .toList());

    var skuMap = new HashMap<>(TableSchema.fromBean(InventorySkuItem.class).itemToMap(sku, true));
    skuMap.put("dirty", AttributeValue.builder().bool(true).build());

    var receipt =
        InventoryCreateUnitOperationItem.create(
            user, operationId, skuId, sequence, Locations.format(sequence));

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(
                                TableSchema.fromBean(InventoryUnitItem.class).itemToMap(unit, true))
                            .conditionExpression("attribute_not_exists(pk)")
                            .build())
                    .build(),
                newSku
                    ? TransactWriteItem.builder()
                        .put(
                            Put.builder()
                                .tableName(tableName)
                                .item(skuMap)
                                .conditionExpression("attribute_not_exists(pk)")
                                .build())
                        .build()
                    : TransactWriteItem.builder()
                        .update(
                            Update.builder()
                                .tableName(tableName)
                                .key(
                                    Map.of(
                                        "pk",
                                        AttributeValue.builder().s(sku.getPk()).build(),
                                        "sk",
                                        AttributeValue.builder().s("SKU").build()))
                                .updateExpression(
                                    "SET dirty = :dirty, gsi1pk = :pk, gsi1sk = :sk ADD #version"
                                        + " :one")
                                .conditionExpression("attribute_exists(pk)")
                                .expressionAttributeNames(Map.of("#version", "version"))
                                .expressionAttributeValues(
                                    Map.of(
                                        ":dirty",
                                        AttributeValue.builder().bool(true).build(),
                                        ":pk",
                                        AttributeValue.builder().s(sku.getGsi1pk()).build(),
                                        ":sk",
                                        AttributeValue.builder().s(sku.getGsi1sk()).build(),
                                        ":one",
                                        AttributeValue.builder().n("1").build()))
                                .build())
                        .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(
                                TableSchema.fromBean(InventoryAuditItem.class)
                                    .itemToMap(
                                        InventoryAuditItem.create(
                                            user,
                                            ulidGenerator.generate(),
                                            "create",
                                            skuId,
                                            sequence,
                                            null,
                                            "absent",
                                            "in_stock",
                                            null,
                                            now),
                                        true))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(
                                TableSchema.fromBean(InventoryCreateUnitOperationItem.class)
                                    .itemToMap(receipt, true))
                            .conditionExpression("attribute_not_exists(pk)")
                            .build())
                    .build())
            .build());

    return receipt;
  }

  @Nullable
  public InventoryReserveUnitOperationItem getReserveUnitOperation(
      String user, String operationId) {
    var key =
        Key.builder()
            .partitionValue("USER#" + user + "#INVENTORY")
            .sortValue("OPERATION#RESERVE_UNIT#" + operationId)
            .build();
    return reserveUnitOperationTable.getItem(request -> request.key(key).consistentRead(true));
  }

  @Nullable
  public InventoryReservationItem getReservation(
      String user, String reservationId, String skuId, int sequenceNumber) {
    var key =
        Key.builder()
            .partitionValue("USER#" + user + "#INVENTORY")
            .sortValue("RESERVATION#" + reservationId + "#SKU#" + skuId + "#UNIT#" + sequenceNumber)
            .build();

    return reservationTable.getItem(request -> request.key(key).consistentRead(true));
  }

  public void releaseUnit(String user, String reservationId, String skuId, int sequenceNumber) {
    var now = clock.now();
    var reservation =
        InventoryReservationItem.create(user, reservationId, skuId, sequenceNumber, "released");
    var audit =
        InventoryAuditItem.create(
            user,
            ulidGenerator.generate(),
            "release",
            skuId,
            sequenceNumber,
            null,
            "reserved",
            "in_stock",
            null,
            now);
    audit.setReservationId(reservationId);

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk",
                                    AttributeValue.builder()
                                        .s(InventoryUnitItem.formatPk(user, skuId))
                                        .build(),
                                    "sk",
                                    AttributeValue.builder()
                                        .s(InventoryUnitItem.formatSk(sequenceNumber))
                                        .build()))
                            .updateExpression(
                                "SET #status = :inStock, updated_at = :now REMOVE reservation_id")
                            .conditionExpression(
                                "attribute_exists(pk) AND #status = :reserved AND"
                                    + " reservation_id = :owner")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(
                                Map.of(
                                    ":inStock",
                                    AttributeValue.builder().s("in_stock").build(),
                                    ":reserved",
                                    AttributeValue.builder().s("reserved").build(),
                                    ":owner",
                                    AttributeValue.builder().s(reservationId).build(),
                                    ":now",
                                    AttributeValue.builder()
                                        .n(Long.toString(now.getEpochSecond()))
                                        .build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk",
                                    AttributeValue.builder()
                                        .s(InventorySkuItem.formatPk(user, skuId))
                                        .build(),
                                    "sk",
                                    AttributeValue.builder().s("SKU").build()))
                            .updateExpression(
                                "SET dirty = :dirty, gsi1pk = :pk, gsi1sk = :sk ADD #version :one")
                            .conditionExpression("attribute_exists(pk)")
                            .expressionAttributeNames(Map.of("#version", "version"))
                            .expressionAttributeValues(
                                Map.of(
                                    ":dirty",
                                    AttributeValue.builder().bool(true).build(),
                                    ":pk",
                                    AttributeValue.builder()
                                        .s(InventorySkuItem.formatGsi1pk(user, true))
                                        .build(),
                                    ":sk",
                                    AttributeValue.builder()
                                        .s(InventorySkuItem.formatGsi1skPrefix() + skuId)
                                        .build(),
                                    ":one",
                                    AttributeValue.builder().n("1").build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(
                                TableSchema.fromBean(InventoryAuditItem.class)
                                    .itemToMap(audit, true))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(
                                TableSchema.fromBean(InventoryReservationItem.class)
                                    .itemToMap(reservation, true))
                            .conditionExpression("attribute_not_exists(pk) OR #status = :reserved")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(
                                Map.of(":reserved", AttributeValue.builder().s("reserved").build()))
                            .build())
                    .build())
            .build());
  }

  public void sellUnit(String user, String reservationId, String skuId, int sequenceNumber) {
    var now = clock.now();
    var reservation =
        InventoryReservationItem.create(user, reservationId, skuId, sequenceNumber, "sold");
    var audit =
        InventoryAuditItem.create(
            user,
            ulidGenerator.generate(),
            "sell",
            skuId,
            sequenceNumber,
            null,
            "reserved",
            "sold",
            null,
            now);
    audit.setReservationId(reservationId);

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk",
                                    AttributeValue.builder()
                                        .s(InventoryUnitItem.formatPk(user, skuId))
                                        .build(),
                                    "sk",
                                    AttributeValue.builder()
                                        .s(InventoryUnitItem.formatSk(sequenceNumber))
                                        .build()))
                            .updateExpression("SET #status = :sold, updated_at = :now")
                            .conditionExpression(
                                "attribute_exists(pk) AND #status = :reserved AND"
                                    + " reservation_id = :owner")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(
                                Map.of(
                                    ":sold",
                                    AttributeValue.builder().s("sold").build(),
                                    ":reserved",
                                    AttributeValue.builder().s("reserved").build(),
                                    ":owner",
                                    AttributeValue.builder().s(reservationId).build(),
                                    ":now",
                                    AttributeValue.builder()
                                        .n(Long.toString(now.getEpochSecond()))
                                        .build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk",
                                    AttributeValue.builder()
                                        .s(InventorySkuItem.formatPk(user, skuId))
                                        .build(),
                                    "sk",
                                    AttributeValue.builder().s("SKU").build()))
                            .updateExpression("ADD #version :one")
                            .conditionExpression("attribute_exists(pk)")
                            .expressionAttributeNames(Map.of("#version", "version"))
                            .expressionAttributeValues(
                                Map.of(":one", AttributeValue.builder().n("1").build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(
                                TableSchema.fromBean(InventoryAuditItem.class)
                                    .itemToMap(audit, true))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(
                                TableSchema.fromBean(InventoryReservationItem.class)
                                    .itemToMap(reservation, true))
                            .conditionExpression("attribute_not_exists(pk) OR #status = :reserved")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(
                                Map.of(":reserved", AttributeValue.builder().s("reserved").build()))
                            .build())
                    .build())
            .build());
  }

  public InventoryReserveUnitOperationItem reserveUnit(
      String user, String operationId, String reservationId, String skuId, int sequenceNumber) {
    var now = clock.now();
    var receipt =
        InventoryReserveUnitOperationItem.create(
            user, operationId, reservationId, skuId, sequenceNumber);
    var reservation =
        InventoryReservationItem.create(user, reservationId, skuId, sequenceNumber, "reserved");
    var audit =
        InventoryAuditItem.create(
            user,
            ulidGenerator.generate(),
            "reserve",
            skuId,
            sequenceNumber,
            null,
            "in_stock",
            "reserved",
            null,
            now);
    audit.setReservationId(reservationId);

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk",
                                    AttributeValue.builder()
                                        .s(InventoryUnitItem.formatPk(user, skuId))
                                        .build(),
                                    "sk",
                                    AttributeValue.builder()
                                        .s(InventoryUnitItem.formatSk(sequenceNumber))
                                        .build()))
                            .updateExpression(
                                "SET #status = :reserved, reservation_id = :owner, updated_at ="
                                    + " :now")
                            .conditionExpression(
                                "attribute_exists(pk) AND #status = :inStock AND"
                                    + " attribute_not_exists(reservation_id)")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(
                                Map.of(
                                    ":reserved",
                                    AttributeValue.builder().s("reserved").build(),
                                    ":inStock",
                                    AttributeValue.builder().s("in_stock").build(),
                                    ":owner",
                                    AttributeValue.builder().s(reservationId).build(),
                                    ":now",
                                    AttributeValue.builder()
                                        .n(Long.toString(now.getEpochSecond()))
                                        .build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk",
                                    AttributeValue.builder()
                                        .s(InventorySkuItem.formatPk(user, skuId))
                                        .build(),
                                    "sk",
                                    AttributeValue.builder().s("SKU").build()))
                            .updateExpression(
                                "SET dirty = :dirty, gsi1pk = :pk, gsi1sk = :sk ADD #version :one")
                            .conditionExpression("attribute_exists(pk)")
                            .expressionAttributeNames(Map.of("#version", "version"))
                            .expressionAttributeValues(
                                Map.of(
                                    ":dirty",
                                    AttributeValue.builder().bool(true).build(),
                                    ":pk",
                                    AttributeValue.builder()
                                        .s(InventorySkuItem.formatGsi1pk(user, true))
                                        .build(),
                                    ":sk",
                                    AttributeValue.builder()
                                        .s(InventorySkuItem.formatGsi1skPrefix() + skuId)
                                        .build(),
                                    ":one",
                                    AttributeValue.builder().n("1").build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(
                                TableSchema.fromBean(InventoryAuditItem.class)
                                    .itemToMap(audit, true))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(
                                TableSchema.fromBean(InventoryReservationItem.class)
                                    .itemToMap(reservation, true))
                            .conditionExpression("attribute_not_exists(pk)")
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(
                                TableSchema.fromBean(InventoryReserveUnitOperationItem.class)
                                    .itemToMap(receipt, true))
                            .conditionExpression("attribute_not_exists(pk)")
                            .build())
                    .build())
            .build());
    return receipt;
  }

  public void deleteUnit(String user, String skuId, int sequenceNumber, @Nullable String reason) {
    var skuPk = "USER#" + user + "#SKU#" + skuId;
    var unitSk = "UNIT#%010d".formatted(sequenceNumber);
    var now = clock.now();
    var audit =
        TableSchema.fromBean(InventoryAuditItem.class)
            .itemToMap(
                InventoryAuditItem.create(
                    user,
                    ulidGenerator.generate(),
                    "adjustment",
                    skuId,
                    sequenceNumber,
                    reason == null || reason.isEmpty() ? null : reason,
                    "in_stock",
                    "removed",
                    null,
                    now),
                true);

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk", AttributeValue.builder().s(skuPk).build(),
                                    "sk", AttributeValue.builder().s(unitSk).build()))
                            .updateExpression("SET #status = :removed, updated_at = :now")
                            .conditionExpression("#status = :inStock")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(
                                Map.of(
                                    ":removed",
                                    AttributeValue.builder().s("removed").build(),
                                    ":inStock",
                                    AttributeValue.builder().s("in_stock").build(),
                                    ":now",
                                    AttributeValue.builder()
                                        .n(Long.toString(now.getEpochSecond()))
                                        .build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk", AttributeValue.builder().s(skuPk).build(),
                                    "sk", AttributeValue.builder().s("SKU").build()))
                            .updateExpression(
                                "SET dirty = :dirty, gsi1pk = :gsi1pk ADD #version :one")
                            .conditionExpression("attribute_exists(pk)")
                            .expressionAttributeNames(Map.of("#version", "version"))
                            .expressionAttributeValues(
                                Map.of(
                                    ":dirty", AttributeValue.builder().bool(true).build(),
                                    ":gsi1pk",
                                        AttributeValue.builder()
                                            .s("USER#" + user + "#DIRTY")
                                            .build(),
                                    ":one", AttributeValue.builder().n("1").build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(Put.builder().tableName(tableName).item(audit).build())
                    .build())
            .build());
  }

  public void updateSkuPrice(
      String user, String skuId, @Nullable String beforePrice, String price) {
    var skuPk = InventorySkuItem.formatPk(user, skuId);
    var dirtyGsi1pk = InventorySkuItem.formatGsi1pk(user, true);
    var dirtyGsi1sk = InventorySkuItem.formatGsi1skPrefix() + skuId;
    var now = clock.now();
    var audit =
        TableSchema.fromBean(InventoryAuditItem.class)
            .itemToMap(
                InventoryAuditItem.createPriceUpdate(
                    user, ulidGenerator.generate(), skuId, beforePrice, price, now),
                true);

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk", AttributeValue.builder().s(skuPk).build(),
                                    "sk", AttributeValue.builder().s("SKU").build()))
                            .updateExpression(
                                "SET suggested_price = :price, dirty = :dirty, "
                                    + "gsi1pk = :gsi1pk, gsi1sk = :gsi1sk ADD #version :one")
                            .conditionExpression("attribute_exists(pk)")
                            .expressionAttributeNames(Map.of("#version", "version"))
                            .expressionAttributeValues(
                                Map.of(
                                    ":price", AttributeValue.builder().s(price).build(),
                                    ":dirty", AttributeValue.builder().bool(true).build(),
                                    ":gsi1pk", AttributeValue.builder().s(dirtyGsi1pk).build(),
                                    ":gsi1sk", AttributeValue.builder().s(dirtyGsi1sk).build(),
                                    ":one", AttributeValue.builder().n("1").build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(Put.builder().tableName(tableName).item(audit).build())
                    .build())
            .build());
  }

  public void clearSkuDirty(String user, String skuId, int version) {
    var skuPk = InventorySkuItem.formatPk(user, skuId);
    dynamoDbClient.updateItem(
        request ->
            request
                .tableName(tableName)
                .key(
                    Map.of(
                        "pk", AttributeValue.builder().s(skuPk).build(),
                        "sk", AttributeValue.builder().s("SKU").build()))
                .updateExpression("SET dirty = :clean, gsi1pk = :gsi1pk")
                .conditionExpression(
                    "attribute_exists(pk) AND dirty = :dirty AND "
                        + "(#version = :version OR (attribute_not_exists(#version) "
                        + "AND :version = :zero))")
                .expressionAttributeNames(Map.of("#version", InventorySkuItem.VERSION))
                .expressionAttributeValues(
                    Map.of(
                        ":clean", AttributeValue.builder().bool(false).build(),
                        ":dirty", AttributeValue.builder().bool(true).build(),
                        ":gsi1pk",
                            AttributeValue.builder()
                                .s(InventorySkuItem.formatGsi1pk(user, false))
                                .build(),
                        ":version", AttributeValue.builder().n(Integer.toString(version)).build(),
                        ":zero", AttributeValue.builder().n("0").build())));
  }

  public void markSkuDirty(String user, String skuId, int version) {
    var skuPk = InventorySkuItem.formatPk(user, skuId);
    dynamoDbClient.updateItem(
        request ->
            request
                .tableName(tableName)
                .key(
                    Map.of(
                        "pk", AttributeValue.builder().s(skuPk).build(),
                        "sk", AttributeValue.builder().s("SKU").build()))
                .updateExpression(
                    "SET dirty = :dirty, gsi1pk = :gsi1pk, gsi1sk = :gsi1sk " + "ADD #version :one")
                .conditionExpression(
                    "attribute_exists(pk) AND "
                        + "(#version = :version OR (attribute_not_exists(#version) "
                        + "AND :version = :zero))")
                .expressionAttributeNames(Map.of("#version", InventorySkuItem.VERSION))
                .expressionAttributeValues(
                    Map.of(
                        ":dirty", AttributeValue.builder().bool(true).build(),
                        ":gsi1pk",
                            AttributeValue.builder()
                                .s(InventorySkuItem.formatGsi1pk(user, true))
                                .build(),
                        ":gsi1sk",
                            AttributeValue.builder()
                                .s(InventorySkuItem.formatGsi1skPrefix() + skuId)
                                .build(),
                        ":version", AttributeValue.builder().n(Integer.toString(version)).build(),
                        ":zero", AttributeValue.builder().n("0").build(),
                        ":one", AttributeValue.builder().n("1").build())));
  }

  public void updateUnitCondition(
      String user,
      InventorySkuItem sourceSku,
      InventoryUnitItem sourceUnit,
      String destinationSkuId,
      String condition) {
    var now = clock.now();
    var sourcePk = InventorySkuItem.formatPk(user, sourceSku.getSkuId());
    var destinationPk = InventorySkuItem.formatPk(user, destinationSkuId);
    var sequenceNumber = sourceUnit.getSequenceNumber();
    var movedUnit = new InventoryUnitItem();
    movedUnit.setPk(destinationPk);
    movedUnit.setSk(InventoryUnitItem.formatSk(sequenceNumber));
    movedUnit.setSequenceNumber(sequenceNumber);
    movedUnit.setGame(sourceUnit.getGame());
    movedUnit.setGsi3pk("USER#" + user + "#UNITS#" + sourceUnit.getGame());
    movedUnit.setStatus("in_stock");
    movedUnit.setCreatedAt(sourceUnit.getCreatedAt());
    movedUnit.setUpdatedAt(now);
    movedUnit.setPhotos(sourceUnit.getPhotos());

    var name = sourceSku.getName();
    var audit =
        TableSchema.fromBean(InventoryAuditItem.class)
            .itemToMap(
                InventoryAuditItem.create(
                    user,
                    ulidGenerator.generate(),
                    "adjustment",
                    sourceSku.getSkuId(),
                    sequenceNumber,
                    null,
                    "in_stock",
                    "in_stock",
                    destinationSkuId,
                    now),
                true);

    var sourceValues =
        Map.of(
            ":dirty", AttributeValue.builder().bool(true).build(),
            ":dirtyPk", AttributeValue.builder().s("USER#" + user + "#DIRTY").build(),
            ":one", AttributeValue.builder().n("1").build());
    var destinationValues = new HashMap<String, AttributeValue>();
    destinationValues.put(":dirty", AttributeValue.builder().bool(true).build());
    destinationValues.put(
        ":dirtyPk", AttributeValue.builder().s("USER#" + user + "#DIRTY").build());
    destinationValues.put(":one", AttributeValue.builder().n("1").build());
    destinationValues.put(":game", AttributeValue.builder().s(sourceSku.getGame()).build());
    destinationValues.put(
        ":externalId", AttributeValue.builder().s(sourceSku.getExternalId()).build());
    destinationValues.put(":name", AttributeValue.builder().s(name).build());
    destinationValues.put(":finish", AttributeValue.builder().s(sourceSku.getFinish()).build());
    destinationValues.put(":condition", AttributeValue.builder().s(condition).build());
    destinationValues.put(":skuId", AttributeValue.builder().s(destinationSkuId).build());
    destinationValues.put(
        ":browsePk", AttributeValue.builder().s("USER#" + user + "#SKUS").build());
    destinationValues.put(
        ":browseSk",
        AttributeValue.builder()
            .s(
                "GAME#"
                    + sourceSku.getGame()
                    + "#NAME#"
                    + name.toLowerCase(Locale.ROOT)
                    + "#"
                    + destinationSkuId)
            .build());
    destinationValues.put(
        ":dirtySk", AttributeValue.builder().s("SKU#" + destinationSkuId).build());
    var destinationExpression =
        new StringBuilder(
            "SET sku_id = if_not_exists(sku_id, :skuId), "
                + "game = if_not_exists(game, :game), "
                + "external_id = if_not_exists(external_id, :externalId), "
                + "#name = if_not_exists(#name, :name), "
                + "#finish = if_not_exists(#finish, :finish), "
                + "#condition = if_not_exists(#condition, :condition), "
                + "gsi1pk = :dirtyPk, gsi1sk = :dirtySk, gsi2pk = :browsePk, "
                + "gsi2sk = if_not_exists(gsi2sk, :browseSk), dirty = :dirty");
    addDestinationField(
        destinationExpression, destinationValues, "set_code", "setCode", sourceSku.getSetCode());
    addDestinationField(
        destinationExpression, destinationValues, "set_name", "setName", sourceSku.getSetName());
    addDestinationField(
        destinationExpression,
        destinationValues,
        "collector_number",
        "collectorNumber",
        sourceSku.getCollectorNumber());
    addDestinationField(
        destinationExpression,
        destinationValues,
        "suggested_price",
        "suggestedPrice",
        sourceSku.getSuggestedPrice());
    addDestinationField(
        destinationExpression,
        destinationValues,
        "fetchtcg_card_id",
        "fetchCardId",
        sourceSku.getFetchtcgCardId());
    destinationExpression.append(", fetchtcg_set_id = if_not_exists(fetchtcg_set_id, :fetchSetId)");
    destinationValues.put(
        ":fetchSetId",
        AttributeValue.builder().n(Integer.toString(sourceSku.getFetchtcgSetId())).build());
    destinationExpression.append(" ADD #version :one");

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                TransactWriteItem.builder()
                    .delete(
                        Delete.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk", AttributeValue.builder().s(sourcePk).build(),
                                    "sk", AttributeValue.builder().s(sourceUnit.getSk()).build()))
                            .conditionExpression("#status = :inStock")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(
                                Map.of(":inStock", AttributeValue.builder().s("in_stock").build()))
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(
                        Put.builder()
                            .tableName(tableName)
                            .item(
                                TableSchema.fromBean(InventoryUnitItem.class)
                                    .itemToMap(movedUnit, true))
                            .conditionExpression("attribute_not_exists(pk)")
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk", AttributeValue.builder().s(sourcePk).build(),
                                    "sk", AttributeValue.builder().s("SKU").build()))
                            .updateExpression(
                                "SET dirty = :dirty, gsi1pk = :dirtyPk ADD #version :one")
                            .conditionExpression("attribute_exists(pk)")
                            .expressionAttributeNames(Map.of("#version", "version"))
                            .expressionAttributeValues(sourceValues)
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(tableName)
                            .key(
                                Map.of(
                                    "pk", AttributeValue.builder().s(destinationPk).build(),
                                    "sk", AttributeValue.builder().s("SKU").build()))
                            .updateExpression(destinationExpression.toString())
                            .expressionAttributeNames(
                                Map.of(
                                    "#version",
                                    "version",
                                    "#name",
                                    "name",
                                    "#condition",
                                    "condition",
                                    "#finish",
                                    "finish"))
                            .expressionAttributeValues(destinationValues)
                            .build())
                    .build(),
                TransactWriteItem.builder()
                    .put(Put.builder().tableName(tableName).item(audit).build())
                    .build())
            .build());
  }

  private static void addDestinationField(
      StringBuilder expression,
      Map<String, AttributeValue> values,
      String attribute,
      String valueName,
      @Nullable String value) {
    if (value != null) {
      expression
          .append(", ")
          .append(attribute)
          .append(" = if_not_exists(")
          .append(attribute)
          .append(", :")
          .append(valueName)
          .append(")");
      values.put(":" + valueName, AttributeValue.builder().s(value).build());
    }
  }
}
