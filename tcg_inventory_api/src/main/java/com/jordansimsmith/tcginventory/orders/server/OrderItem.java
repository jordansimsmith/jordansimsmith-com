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
  public static final String BUYER_NAME = "buyer_name";
  public static final String BUYER_ADDRESS = "buyer_address";
  public static final String POSTAGE_OPTION = "postage_option";

  private String pk;
  private String sk;
  private String orderId;
  private String status;
  private String deliveryMode;
  private String totalPrice;
  private Integer rowCount;
  private Instant createdAt;
  private String buyerName;
  private BuyerAddress buyerAddress;
  private String postageOption;

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

  @Nullable
  @DynamoDbAttribute(BUYER_NAME)
  public String getBuyerName() {
    return buyerName;
  }

  public void setBuyerName(@Nullable String value) {
    buyerName = value;
  }

  @Nullable
  @DynamoDbAttribute(BUYER_ADDRESS)
  public BuyerAddress getBuyerAddress() {
    return buyerAddress;
  }

  public void setBuyerAddress(@Nullable BuyerAddress value) {
    buyerAddress = value;
  }

  @Nullable
  @DynamoDbAttribute(POSTAGE_OPTION)
  public String getPostageOption() {
    return postageOption;
  }

  public void setPostageOption(@Nullable String value) {
    postageOption = value;
  }

  @DynamoDbBean
  public static class BuyerAddress {
    public static final String LINE1 = "line1";
    public static final String LINE2 = "line2";
    public static final String SUBURB = "suburb";
    public static final String CITY = "city";
    public static final String POST_CODE = "post_code";
    public static final String COUNTRY = "country";

    private String line1;
    private String line2;
    private String suburb;
    private String city;
    private String postCode;
    private String country;

    @Nullable
    @DynamoDbAttribute(LINE1)
    public String getLine1() {
      return line1;
    }

    public void setLine1(@Nullable String value) {
      line1 = value;
    }

    @Nullable
    @DynamoDbAttribute(LINE2)
    public String getLine2() {
      return line2;
    }

    public void setLine2(@Nullable String value) {
      line2 = value;
    }

    @Nullable
    @DynamoDbAttribute(SUBURB)
    public String getSuburb() {
      return suburb;
    }

    public void setSuburb(@Nullable String value) {
      suburb = value;
    }

    @Nullable
    @DynamoDbAttribute(CITY)
    public String getCity() {
      return city;
    }

    public void setCity(@Nullable String value) {
      city = value;
    }

    @Nullable
    @DynamoDbAttribute(POST_CODE)
    public String getPostCode() {
      return postCode;
    }

    public void setPostCode(@Nullable String value) {
      postCode = value;
    }

    @Nullable
    @DynamoDbAttribute(COUNTRY)
    public String getCountry() {
      return country;
    }

    public void setCountry(@Nullable String value) {
      country = value;
    }
  }

  public static String formatPk(String user) {
    return "USER#" + user;
  }

  public static String formatSk(String orderId) {
    return ORDER_PREFIX + "0".repeat(Math.max(0, 20 - orderId.length())) + orderId;
  }
}
