package com.jordansimsmith.tcginventory.orders;

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
  public static final String FETCHTCG_LISTING_ID = "fetchtcg_listing_id";
  public static final String QUANTITY = "quantity";
  public static final String PRICE = "price";
  public static final String LISTED_PRICE = "listed_price";
  public static final String ALLOCATED_SEQUENCE_NUMBERS = "allocated_sequence_numbers";

  private String pk;
  private String sk;
  private String user;
  private String orderId;
  private int position;
  private String skuId;
  private int fetchtcgListingId;
  private int quantity;
  private String price;
  private String listedPrice;
  private List<Integer> allocatedSequenceNumbers;

  public OrderRowItem() {}

  public OrderRowItem(
      String skuId,
      int fetchtcgListingId,
      int quantity,
      @Nullable String price,
      @Nullable String listedPrice,
      List<Integer> allocatedSequenceNumbers) {
    this.skuId = skuId;
    this.fetchtcgListingId = fetchtcgListingId;
    this.quantity = quantity;
    this.price = price;
    this.listedPrice = listedPrice;
    this.allocatedSequenceNumbers = allocatedSequenceNumbers;
  }

  @DynamoDbAttribute(SKU_ID)
  public String getSkuId() {
    return skuId;
  }

  public void setSkuId(String skuId) {
    this.skuId = skuId;
  }

  @DynamoDbAttribute(FETCHTCG_LISTING_ID)
  public int getFetchtcgListingId() {
    return fetchtcgListingId;
  }

  public void setFetchtcgListingId(int fetchtcgListingId) {
    this.fetchtcgListingId = fetchtcgListingId;
  }

  @DynamoDbAttribute(QUANTITY)
  public int getQuantity() {
    return quantity;
  }

  public void setQuantity(int quantity) {
    this.quantity = quantity;
  }

  @Nullable
  @DynamoDbAttribute(PRICE)
  public String getPrice() {
    return price;
  }

  public void setPrice(@Nullable String price) {
    this.price = price;
  }

  @Nullable
  @DynamoDbAttribute(LISTED_PRICE)
  public String getListedPrice() {
    return listedPrice;
  }

  public void setListedPrice(@Nullable String listedPrice) {
    this.listedPrice = listedPrice;
  }

  @DynamoDbAttribute(ALLOCATED_SEQUENCE_NUMBERS)
  public List<Integer> getAllocatedSequenceNumbers() {
    return allocatedSequenceNumbers;
  }

  public void setAllocatedSequenceNumbers(List<Integer> allocatedSequenceNumbers) {
    this.allocatedSequenceNumbers = allocatedSequenceNumbers;
  }

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
  public int getPosition() {
    return position;
  }

  public void setPosition(int value) {
    position = value;
  }

  public static String formatPk(String user, String orderId) {
    return "USER#" + user + "#ORDER#" + orderId;
  }

  public static String formatSk(int position) {
    return "ROW#" + String.format(Locale.ROOT, "%010d", position);
  }

  public static OrderRowItem create(
      String user,
      String orderId,
      int position,
      String skuId,
      int listingId,
      int quantity,
      @Nullable String price,
      @Nullable String listedPrice,
      List<Integer> sequences) {
    var item = new OrderRowItem(skuId, listingId, quantity, price, listedPrice, sequences);
    item.setPk(formatPk(user, orderId));
    item.setSk(formatSk(position));
    item.setUser(user);
    item.setOrderId(orderId);
    item.setPosition(position);
    return item;
  }
}
