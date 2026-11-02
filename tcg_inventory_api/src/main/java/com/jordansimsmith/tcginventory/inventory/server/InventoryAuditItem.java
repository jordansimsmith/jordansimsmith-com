package com.jordansimsmith.tcginventory.inventory.server;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class InventoryAuditItem {
  private String pk;
  private String sk;
  private String eventType;
  private String skuId;
  private Integer sequenceNumber;
  private String decisionReason;
  private String beforeStatus;
  private String afterStatus;
  private String targetSkuId;
  private String beforePrice;
  private String afterPrice;
  private Instant createdAt;

  @DynamoDbPartitionKey
  @DynamoDbAttribute("pk")
  public String getPk() {
    return pk;
  }

  public void setPk(String pk) {
    this.pk = pk;
  }

  @DynamoDbSortKey
  @DynamoDbAttribute("sk")
  public String getSk() {
    return sk;
  }

  public void setSk(String sk) {
    this.sk = sk;
  }

  @DynamoDbAttribute("event_type")
  public String getEventType() {
    return eventType;
  }

  public void setEventType(String eventType) {
    this.eventType = eventType;
  }

  @DynamoDbAttribute("sku_id")
  public String getSkuId() {
    return skuId;
  }

  public void setSkuId(String skuId) {
    this.skuId = skuId;
  }

  @DynamoDbAttribute("sequence_number")
  public Integer getSequenceNumber() {
    return sequenceNumber;
  }

  public void setSequenceNumber(@Nullable Integer sequenceNumber) {
    this.sequenceNumber = sequenceNumber;
  }

  @DynamoDbAttribute("decision_reason")
  public String getDecisionReason() {
    return decisionReason;
  }

  public void setDecisionReason(@Nullable String decisionReason) {
    this.decisionReason = decisionReason;
  }

  @DynamoDbAttribute("before_status")
  public String getBeforeStatus() {
    return beforeStatus;
  }

  public void setBeforeStatus(String beforeStatus) {
    this.beforeStatus = beforeStatus;
  }

  @DynamoDbAttribute("after_status")
  public String getAfterStatus() {
    return afterStatus;
  }

  public void setAfterStatus(String afterStatus) {
    this.afterStatus = afterStatus;
  }

  @DynamoDbAttribute("target_sku_id")
  public String getTargetSkuId() {
    return targetSkuId;
  }

  public void setTargetSkuId(@Nullable String targetSkuId) {
    this.targetSkuId = targetSkuId;
  }

  @DynamoDbAttribute("before_price")
  public String getBeforePrice() {
    return beforePrice;
  }

  public void setBeforePrice(@Nullable String beforePrice) {
    this.beforePrice = beforePrice;
  }

  @DynamoDbAttribute("after_price")
  public String getAfterPrice() {
    return afterPrice;
  }

  public void setAfterPrice(@Nullable String afterPrice) {
    this.afterPrice = afterPrice;
  }

  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute("created_at")
  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public static InventoryAuditItem create(
      String user,
      String id,
      String eventType,
      String skuId,
      int sequenceNumber,
      @Nullable String decisionReason,
      String beforeStatus,
      String afterStatus,
      @Nullable String targetSkuId,
      Instant createdAt) {
    var item = new InventoryAuditItem();
    item.setPk("USER#" + user + "#AUDIT");
    item.setSk(id);
    item.setEventType(eventType);
    item.setSkuId(skuId);
    item.setSequenceNumber(sequenceNumber);
    item.setDecisionReason(decisionReason);
    item.setBeforeStatus(beforeStatus);
    item.setAfterStatus(afterStatus);
    item.setTargetSkuId(targetSkuId);
    item.setCreatedAt(createdAt);
    return item;
  }

  public static InventoryAuditItem createPriceUpdate(
      String user,
      String id,
      String skuId,
      @Nullable String beforePrice,
      String afterPrice,
      Instant createdAt) {
    var item = new InventoryAuditItem();
    item.setPk("USER#" + user + "#AUDIT");
    item.setSk(id);
    item.setEventType("price_update");
    item.setSkuId(skuId);
    item.setBeforePrice(beforePrice);
    item.setAfterPrice(afterPrice);
    item.setCreatedAt(createdAt);
    return item;
  }
}
