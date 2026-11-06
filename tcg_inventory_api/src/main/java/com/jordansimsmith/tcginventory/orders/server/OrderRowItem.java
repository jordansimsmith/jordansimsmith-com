package com.jordansimsmith.tcginventory.orders.server;

import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class OrderRowItem {
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String USER = "user";
  public static final String ORDER_ID = "order_id";
  public static final String POSITION = "position";
  public static final String SKU_ID = "sku_id";
  public static final String QUANTITY = "quantity";
  public static final String PRICE = "price";
  public static final String LISTED_PRICE = "listed_price";
  public static final String ALLOCATED_SEQUENCE_NUMBERS = "allocated_sequence_numbers";

  private String pk;
  private String sk;
  private String user;
  private String orderId;
  private Integer position;
  private String skuId;
  private Integer quantity;
  private String price;
  private String listedPrice;
  private List<Integer> allocatedSequenceNumbers;

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

  @DynamoDbAttribute(USER)
  public String getUser() {
    return user;
  }

  public void setUser(String value) {
    user = value;
  }

  @DynamoDbAttribute(ORDER_ID)
  public String getOrderId() {
    return orderId;
  }

  public void setOrderId(String value) {
    orderId = value;
  }

  @DynamoDbAttribute(POSITION)
  public Integer getPosition() {
    return position;
  }

  public void setPosition(Integer value) {
    position = value;
  }

  @DynamoDbAttribute(SKU_ID)
  public String getSkuId() {
    return skuId;
  }

  public void setSkuId(String value) {
    skuId = value;
  }

  @DynamoDbAttribute(QUANTITY)
  public Integer getQuantity() {
    return quantity;
  }

  public void setQuantity(Integer value) {
    quantity = value;
  }

  @Nullable
  @DynamoDbAttribute(PRICE)
  public String getPrice() {
    return price;
  }

  public void setPrice(@Nullable String value) {
    price = value;
  }

  @Nullable
  @DynamoDbAttribute(LISTED_PRICE)
  public String getListedPrice() {
    return listedPrice;
  }

  public void setListedPrice(@Nullable String value) {
    listedPrice = value;
  }

  @Nullable
  @DynamoDbAttribute(ALLOCATED_SEQUENCE_NUMBERS)
  public List<Integer> getAllocatedSequenceNumbers() {
    return allocatedSequenceNumbers;
  }

  public void setAllocatedSequenceNumbers(@Nullable List<Integer> value) {
    allocatedSequenceNumbers = value;
  }

  public static String formatPk(String user, String orderId) {
    return "USER#" + user + "#ORDER#" + orderId;
  }

  public static String formatSk(int position) {
    return "ROW#" + String.format(Locale.ROOT, "%010d", position);
  }
}
