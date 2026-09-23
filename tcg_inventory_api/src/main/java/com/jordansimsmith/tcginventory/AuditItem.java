package com.jordansimsmith.tcginventory;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class AuditItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String AUDIT_SUFFIX = "AUDIT";
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String EVENT_TYPE = "event_type";
  public static final String IMPORT_ID = "import_id";
  public static final String SKU_ID = "sku_id";
  public static final String SEQUENCE_NUMBER = "sequence_number";
  public static final String ORDER_ID = "order_id";
  public static final String DECISION_REASON = "decision_reason";
  public static final String CREATED_AT = "created_at";

  private String pk;
  private String sk;
  private String eventType;
  private String importId;
  private String skuId;
  private Integer sequenceNumber;
  private String orderId;
  private String decisionReason;
  private Instant createdAt;

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

  @DynamoDbAttribute(EVENT_TYPE)
  public String getEventType() {
    return eventType;
  }

  public void setEventType(@Nullable String eventType) {
    this.eventType = eventType;
  }

  @DynamoDbAttribute(IMPORT_ID)
  public String getImportId() {
    return importId;
  }

  public void setImportId(@Nullable String importId) {
    this.importId = importId;
  }

  @DynamoDbAttribute(SKU_ID)
  public String getSkuId() {
    return skuId;
  }

  public void setSkuId(@Nullable String skuId) {
    this.skuId = skuId;
  }

  @DynamoDbAttribute(SEQUENCE_NUMBER)
  public Integer getSequenceNumber() {
    return sequenceNumber;
  }

  public void setSequenceNumber(@Nullable Integer sequenceNumber) {
    this.sequenceNumber = sequenceNumber;
  }

  @DynamoDbAttribute(ORDER_ID)
  public String getOrderId() {
    return orderId;
  }

  public void setOrderId(@Nullable String orderId) {
    this.orderId = orderId;
  }

  @DynamoDbAttribute(DECISION_REASON)
  public String getDecisionReason() {
    return decisionReason;
  }

  public void setDecisionReason(@Nullable String decisionReason) {
    this.decisionReason = decisionReason;
  }

  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(CREATED_AT)
  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(@Nullable Instant createdAt) {
    this.createdAt = createdAt;
  }

  public static String formatPk(String user) {
    return USER_PREFIX + user + DELIMITER + AUDIT_SUFFIX;
  }
}
