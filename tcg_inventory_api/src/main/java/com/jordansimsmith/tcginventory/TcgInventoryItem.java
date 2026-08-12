package com.jordansimsmith.tcginventory;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import java.util.Objects;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class TcgInventoryItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String SKU_PREFIX = "SKU" + DELIMITER;
  public static final String UNIT_PREFIX = "UNIT" + DELIMITER;
  public static final String IMPORT_PREFIX = "IMPORT" + DELIMITER;
  public static final String ROW_PREFIX = "ROW" + DELIMITER;
  public static final String ORDER_PREFIX = "ORDER" + DELIMITER;
  public static final String JOB_PREFIX = "JOB" + DELIMITER;
  public static final String COUNTER_PREFIX = "COUNTER" + DELIMITER;
  public static final String DIRTY_SUFFIX = "DIRTY";
  public static final String SKUS_SUFFIX = "SKUS";
  public static final String NAME_PREFIX = "NAME" + DELIMITER;
  public static final String AUDIT_SUFFIX = "AUDIT";

  public static final String TABLE_NAME = "tcg_inventory";
  public static final String GSI1_NAME = "gsi1";
  public static final String GSI2_NAME = "gsi2";

  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String GSI1PK = "gsi1pk";
  public static final String GSI1SK = "gsi1sk";
  public static final String GSI2PK = "gsi2pk";
  public static final String GSI2SK = "gsi2sk";
  public static final String SKU_ID = "sku_id";
  public static final String SCRYFALL_ID = "scryfall_id";
  public static final String FINISH = "finish";
  public static final String CONDITION = "condition";
  public static final String NAME = "name";
  public static final String SET_CODE = "set_code";
  public static final String SET_NAME = "set_name";
  public static final String COLLECTOR_NUMBER = "collector_number";
  public static final String FETCHTCG_CARD_ID = "fetchtcg_card_id";
  public static final String FETCHTCG_SET_ID = "fetchtcg_set_id";
  public static final String IN_STOCK_COUNT = "in_stock_count";
  public static final String RESERVED_COUNT = "reserved_count";
  public static final String SOLD_COUNT = "sold_count";
  public static final String DIRTY = "dirty";
  public static final String SEQUENCE_NUMBER = "sequence_number";
  public static final String STATUS = "status";
  public static final String IMPORT_ID = "import_id";
  public static final String ORDER_ID = "order_id";
  public static final String FILENAME = "filename";
  public static final String NEXT_SEQUENCE_NUMBER = "next_sequence_number";
  public static final String CREATED_AT = "created_at";
  public static final String UPDATED_AT = "updated_at";

  private String pk;
  private String sk;
  private String gsi1pk;
  private String gsi1sk;
  private String gsi2pk;
  private String gsi2sk;
  private String skuId;
  private String scryfallId;
  private String finish;
  private String condition;
  private String name;
  private String setCode;
  private String setName;
  private String collectorNumber;
  private Integer fetchtcgCardId;
  private Integer fetchtcgSetId;
  private Integer inStockCount;
  private Integer reservedCount;
  private Integer soldCount;
  private Boolean dirty;
  private Integer sequenceNumber;
  private String status;
  private String importId;
  private String orderId;
  private String filename;
  private Integer nextSequenceNumber;
  private Instant createdAt;
  private Instant updatedAt;

  @DynamoDbPartitionKey
  @DynamoDbAttribute(PK)
  public String getPk() {
    return pk;
  }

  public void setPk(String pk) {
    this.pk = pk;
  }

  @DynamoDbSortKey
  @DynamoDbAttribute(SK)
  public String getSk() {
    return sk;
  }

  public void setSk(String sk) {
    this.sk = sk;
  }

  @Nullable
  @DynamoDbSecondaryPartitionKey(indexNames = GSI1_NAME)
  @DynamoDbAttribute(GSI1PK)
  public String getGsi1pk() {
    return gsi1pk;
  }

  public void setGsi1pk(@Nullable String gsi1pk) {
    this.gsi1pk = gsi1pk;
  }

  @Nullable
  @DynamoDbSecondarySortKey(indexNames = GSI1_NAME)
  @DynamoDbAttribute(GSI1SK)
  public String getGsi1sk() {
    return gsi1sk;
  }

  public void setGsi1sk(@Nullable String gsi1sk) {
    this.gsi1sk = gsi1sk;
  }

  @Nullable
  @DynamoDbSecondaryPartitionKey(indexNames = GSI2_NAME)
  @DynamoDbAttribute(GSI2PK)
  public String getGsi2pk() {
    return gsi2pk;
  }

  public void setGsi2pk(@Nullable String gsi2pk) {
    this.gsi2pk = gsi2pk;
  }

  @Nullable
  @DynamoDbSecondarySortKey(indexNames = GSI2_NAME)
  @DynamoDbAttribute(GSI2SK)
  public String getGsi2sk() {
    return gsi2sk;
  }

  public void setGsi2sk(@Nullable String gsi2sk) {
    this.gsi2sk = gsi2sk;
  }

  @Nullable
  @DynamoDbAttribute(SKU_ID)
  public String getSkuId() {
    return skuId;
  }

  public void setSkuId(@Nullable String skuId) {
    this.skuId = skuId;
  }

  @Nullable
  @DynamoDbAttribute(SCRYFALL_ID)
  public String getScryfallId() {
    return scryfallId;
  }

  public void setScryfallId(@Nullable String scryfallId) {
    this.scryfallId = scryfallId;
  }

  @Nullable
  @DynamoDbAttribute(FINISH)
  public String getFinish() {
    return finish;
  }

  public void setFinish(@Nullable String finish) {
    this.finish = finish;
  }

  @Nullable
  @DynamoDbAttribute(CONDITION)
  public String getCondition() {
    return condition;
  }

  public void setCondition(@Nullable String condition) {
    this.condition = condition;
  }

  @Nullable
  @DynamoDbAttribute(NAME)
  public String getName() {
    return name;
  }

  public void setName(@Nullable String name) {
    this.name = name;
  }

  @Nullable
  @DynamoDbAttribute(SET_CODE)
  public String getSetCode() {
    return setCode;
  }

  public void setSetCode(@Nullable String setCode) {
    this.setCode = setCode;
  }

  @Nullable
  @DynamoDbAttribute(SET_NAME)
  public String getSetName() {
    return setName;
  }

  public void setSetName(@Nullable String setName) {
    this.setName = setName;
  }

  @Nullable
  @DynamoDbAttribute(COLLECTOR_NUMBER)
  public String getCollectorNumber() {
    return collectorNumber;
  }

  public void setCollectorNumber(@Nullable String collectorNumber) {
    this.collectorNumber = collectorNumber;
  }

  @Nullable
  @DynamoDbAttribute(FETCHTCG_CARD_ID)
  public Integer getFetchtcgCardId() {
    return fetchtcgCardId;
  }

  public void setFetchtcgCardId(@Nullable Integer fetchtcgCardId) {
    this.fetchtcgCardId = fetchtcgCardId;
  }

  @Nullable
  @DynamoDbAttribute(FETCHTCG_SET_ID)
  public Integer getFetchtcgSetId() {
    return fetchtcgSetId;
  }

  public void setFetchtcgSetId(@Nullable Integer fetchtcgSetId) {
    this.fetchtcgSetId = fetchtcgSetId;
  }

  @Nullable
  @DynamoDbAttribute(IN_STOCK_COUNT)
  public Integer getInStockCount() {
    return inStockCount;
  }

  public void setInStockCount(@Nullable Integer inStockCount) {
    this.inStockCount = inStockCount;
  }

  @Nullable
  @DynamoDbAttribute(RESERVED_COUNT)
  public Integer getReservedCount() {
    return reservedCount;
  }

  public void setReservedCount(@Nullable Integer reservedCount) {
    this.reservedCount = reservedCount;
  }

  @Nullable
  @DynamoDbAttribute(SOLD_COUNT)
  public Integer getSoldCount() {
    return soldCount;
  }

  public void setSoldCount(@Nullable Integer soldCount) {
    this.soldCount = soldCount;
  }

  @Nullable
  @DynamoDbAttribute(DIRTY)
  public Boolean getDirty() {
    return dirty;
  }

  public void setDirty(@Nullable Boolean dirty) {
    this.dirty = dirty;
  }

  @Nullable
  @DynamoDbAttribute(SEQUENCE_NUMBER)
  public Integer getSequenceNumber() {
    return sequenceNumber;
  }

  public void setSequenceNumber(@Nullable Integer sequenceNumber) {
    this.sequenceNumber = sequenceNumber;
  }

  @Nullable
  @DynamoDbAttribute(STATUS)
  public String getStatus() {
    return status;
  }

  public void setStatus(@Nullable String status) {
    this.status = status;
  }

  @Nullable
  @DynamoDbAttribute(IMPORT_ID)
  public String getImportId() {
    return importId;
  }

  public void setImportId(@Nullable String importId) {
    this.importId = importId;
  }

  @Nullable
  @DynamoDbAttribute(ORDER_ID)
  public String getOrderId() {
    return orderId;
  }

  public void setOrderId(@Nullable String orderId) {
    this.orderId = orderId;
  }

  @Nullable
  @DynamoDbAttribute(FILENAME)
  public String getFilename() {
    return filename;
  }

  public void setFilename(@Nullable String filename) {
    this.filename = filename;
  }

  @Nullable
  @DynamoDbAttribute(NEXT_SEQUENCE_NUMBER)
  public Integer getNextSequenceNumber() {
    return nextSequenceNumber;
  }

  public void setNextSequenceNumber(@Nullable Integer nextSequenceNumber) {
    this.nextSequenceNumber = nextSequenceNumber;
  }

  @Nullable
  @DynamoDbAttribute(CREATED_AT)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(@Nullable Instant createdAt) {
    this.createdAt = createdAt;
  }

  @Nullable
  @DynamoDbAttribute(UPDATED_AT)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(@Nullable Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TcgInventoryItem that = (TcgInventoryItem) o;
    return Objects.equals(pk, that.pk)
        && Objects.equals(sk, that.sk)
        && Objects.equals(gsi1pk, that.gsi1pk)
        && Objects.equals(gsi1sk, that.gsi1sk)
        && Objects.equals(gsi2pk, that.gsi2pk)
        && Objects.equals(gsi2sk, that.gsi2sk)
        && Objects.equals(skuId, that.skuId)
        && Objects.equals(scryfallId, that.scryfallId)
        && Objects.equals(finish, that.finish)
        && Objects.equals(condition, that.condition)
        && Objects.equals(name, that.name)
        && Objects.equals(setCode, that.setCode)
        && Objects.equals(setName, that.setName)
        && Objects.equals(collectorNumber, that.collectorNumber)
        && Objects.equals(fetchtcgCardId, that.fetchtcgCardId)
        && Objects.equals(fetchtcgSetId, that.fetchtcgSetId)
        && Objects.equals(inStockCount, that.inStockCount)
        && Objects.equals(reservedCount, that.reservedCount)
        && Objects.equals(soldCount, that.soldCount)
        && Objects.equals(dirty, that.dirty)
        && Objects.equals(sequenceNumber, that.sequenceNumber)
        && Objects.equals(status, that.status)
        && Objects.equals(importId, that.importId)
        && Objects.equals(orderId, that.orderId)
        && Objects.equals(filename, that.filename)
        && Objects.equals(nextSequenceNumber, that.nextSequenceNumber)
        && Objects.equals(createdAt, that.createdAt)
        && Objects.equals(updatedAt, that.updatedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        pk,
        sk,
        gsi1pk,
        gsi1sk,
        gsi2pk,
        gsi2sk,
        skuId,
        scryfallId,
        finish,
        condition,
        name,
        setCode,
        setName,
        collectorNumber,
        fetchtcgCardId,
        fetchtcgSetId,
        inStockCount,
        reservedCount,
        soldCount,
        dirty,
        sequenceNumber,
        status,
        importId,
        orderId,
        filename,
        nextSequenceNumber,
        createdAt,
        updatedAt);
  }

  @Override
  public String toString() {
    return "TcgInventoryItem{"
        + "pk='"
        + pk
        + '\''
        + ", sk='"
        + sk
        + '\''
        + ", gsi1pk='"
        + gsi1pk
        + '\''
        + ", gsi1sk='"
        + gsi1sk
        + '\''
        + ", gsi2pk='"
        + gsi2pk
        + '\''
        + ", gsi2sk='"
        + gsi2sk
        + '\''
        + ", skuId='"
        + skuId
        + '\''
        + ", scryfallId='"
        + scryfallId
        + '\''
        + ", finish='"
        + finish
        + '\''
        + ", condition='"
        + condition
        + '\''
        + ", name='"
        + name
        + '\''
        + ", setCode='"
        + setCode
        + '\''
        + ", setName='"
        + setName
        + '\''
        + ", collectorNumber='"
        + collectorNumber
        + '\''
        + ", fetchtcgCardId="
        + fetchtcgCardId
        + ", fetchtcgSetId="
        + fetchtcgSetId
        + ", inStockCount="
        + inStockCount
        + ", reservedCount="
        + reservedCount
        + ", soldCount="
        + soldCount
        + ", dirty="
        + dirty
        + ", sequenceNumber="
        + sequenceNumber
        + ", status='"
        + status
        + '\''
        + ", importId='"
        + importId
        + '\''
        + ", orderId='"
        + orderId
        + '\''
        + ", filename='"
        + filename
        + '\''
        + ", nextSequenceNumber="
        + nextSequenceNumber
        + ", createdAt="
        + createdAt
        + ", updatedAt="
        + updatedAt
        + '}';
  }

  public static String formatSkuPk(String user, String skuId) {
    return USER_PREFIX + user + DELIMITER + SKU_PREFIX + skuId;
  }

  public static String formatSkuSk() {
    return "SKU";
  }

  public static String formatUnitSk(int sequenceNumber) {
    return UNIT_PREFIX + String.format("%010d", sequenceNumber);
  }

  public static String formatImportPk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatImportSk(String importId) {
    return IMPORT_PREFIX + importId;
  }

  public static String formatImportRowPk(String user, String importId) {
    return USER_PREFIX + user + DELIMITER + IMPORT_PREFIX + importId;
  }

  public static String formatImportRowSk(int stackPosition) {
    return ROW_PREFIX + String.format("%010d", stackPosition);
  }

  public static String formatOrderSk(String offerId) {
    return ORDER_PREFIX + offerId;
  }

  public static String formatJobSk(String jobId) {
    return JOB_PREFIX + jobId;
  }

  public static String formatCounterSk() {
    return COUNTER_PREFIX + "SEQUENCE";
  }

  public static String formatSettingsSk() {
    return "SETTINGS";
  }

  public static String formatAuditPk(String user) {
    return USER_PREFIX + user + DELIMITER + AUDIT_SUFFIX;
  }

  public static String formatGsi1pk(String user) {
    return USER_PREFIX + user + DELIMITER + DIRTY_SUFFIX;
  }

  public static String formatGsi1sk(String skuId) {
    return SKU_PREFIX + skuId;
  }

  public static String formatGsi2pk(String user) {
    return USER_PREFIX + user + DELIMITER + SKUS_SUFFIX;
  }

  public static String formatGsi2sk(String normalizedName, String skuId) {
    return NAME_PREFIX + normalizedName + DELIMITER + skuId;
  }
}
