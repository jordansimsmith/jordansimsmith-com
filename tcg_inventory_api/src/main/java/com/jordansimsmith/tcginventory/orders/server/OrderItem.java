package com.jordansimsmith.tcginventory.orders.server;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class OrderItem {
  public static final String ORDER_PREFIX = "ORDER#";
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String ORDER_ID = "order_id";
  public static final String STATUS = "status";
  public static final String DELIVERY_MODE = "delivery_mode";
  public static final String TOTAL_PRICE = "total_price";
  public static final String ROW_COUNT = "row_count";
  public static final String CREATED_AT = "created_at";

  private String pk;
  private String sk;
  private String orderId;
  private String status;
  private String deliveryMode;
  private String totalPrice;
  private Integer rowCount;
  private Instant createdAt;

  @DynamoDbPartitionKey
  @DynamoDbAttribute(PK)
  public String getPk() {
    return pk;
  }

  public void setPk(String value) {
    pk = value;
  }

  @DynamoDbSortKey
  @DynamoDbAttribute(SK)
  public String getSk() {
    return sk;
  }

  public void setSk(String value) {
    sk = value;
  }

  @DynamoDbAttribute(ORDER_ID)
  public String getOrderId() {
    return orderId;
  }

  public void setOrderId(String value) {
    orderId = value;
  }

  @DynamoDbAttribute(STATUS)
  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    status = value;
  }

  @Nullable
  @DynamoDbAttribute(DELIVERY_MODE)
  public String getDeliveryMode() {
    return deliveryMode;
  }

  public void setDeliveryMode(@Nullable String value) {
    deliveryMode = value;
  }

  @Nullable
  @DynamoDbAttribute(TOTAL_PRICE)
  public String getTotalPrice() {
    return totalPrice;
  }

  public void setTotalPrice(@Nullable String value) {
    totalPrice = value;
  }

  @DynamoDbAttribute(ROW_COUNT)
  public Integer getRowCount() {
    return rowCount;
  }

  public void setRowCount(Integer value) {
    rowCount = value;
  }

  @Nullable
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(CREATED_AT)
  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(@Nullable Instant value) {
    createdAt = value;
  }

  public static String formatPk(String user) {
    return "USER#" + user;
  }
}
