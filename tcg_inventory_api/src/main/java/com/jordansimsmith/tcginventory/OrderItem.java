package com.jordansimsmith.tcginventory;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class OrderItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String ORDER_PREFIX = "ORDER" + DELIMITER;
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String ORDER_ID = "order_id";
  public static final String STATUS = "status";
  public static final String FETCHTCG_STATUS = "fetchtcg_status";
  public static final String FETCHTCG_CURRENT_ACTION = "fetchtcg_current_action";
  public static final String DELIVERY_MODE = "delivery_mode";
  public static final String BUYER_NAME = "buyer_name";
  public static final String BUYER_ADDRESS = "buyer_address";
  public static final String POSTAGE_OPTION = "postage_option";
  public static final String TOTAL_PRICE = "total_price";
  public static final String LINES = "lines";
  public static final String CREATED_AT = "created_at";
  public static final String UPDATED_AT = "updated_at";

  private String pk;
  private String sk;
  private String orderId;
  private String status;
  private String fetchtcgStatus;
  private String fetchtcgCurrentAction;
  private String deliveryMode;
  private String buyerName;
  private BuyerAddress buyerAddress;
  private String postageOption;
  private String totalPrice;
  private String lines;
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

  @DynamoDbAttribute(ORDER_ID)
  public String getOrderId() {
    return orderId;
  }

  public void setOrderId(@Nullable String orderId) {
    this.orderId = orderId;
  }

  @DynamoDbAttribute(STATUS)
  public String getStatus() {
    return status;
  }

  public void setStatus(@Nullable String status) {
    this.status = status;
  }

  @DynamoDbAttribute(FETCHTCG_STATUS)
  public String getFetchtcgStatus() {
    return fetchtcgStatus;
  }

  public void setFetchtcgStatus(@Nullable String fetchtcgStatus) {
    this.fetchtcgStatus = fetchtcgStatus;
  }

  @DynamoDbAttribute(FETCHTCG_CURRENT_ACTION)
  public String getFetchtcgCurrentAction() {
    return fetchtcgCurrentAction;
  }

  public void setFetchtcgCurrentAction(@Nullable String fetchtcgCurrentAction) {
    this.fetchtcgCurrentAction = fetchtcgCurrentAction;
  }

  @DynamoDbAttribute(DELIVERY_MODE)
  public String getDeliveryMode() {
    return deliveryMode;
  }

  public void setDeliveryMode(@Nullable String deliveryMode) {
    this.deliveryMode = deliveryMode;
  }

  @DynamoDbAttribute(BUYER_NAME)
  public String getBuyerName() {
    return buyerName;
  }

  public void setBuyerName(@Nullable String buyerName) {
    this.buyerName = buyerName;
  }

  @DynamoDbAttribute(BUYER_ADDRESS)
  public BuyerAddress getBuyerAddress() {
    return buyerAddress;
  }

  public void setBuyerAddress(@Nullable BuyerAddress buyerAddress) {
    this.buyerAddress = buyerAddress;
  }

  @DynamoDbAttribute(POSTAGE_OPTION)
  public String getPostageOption() {
    return postageOption;
  }

  public void setPostageOption(@Nullable String postageOption) {
    this.postageOption = postageOption;
  }

  @DynamoDbAttribute(TOTAL_PRICE)
  public String getTotalPrice() {
    return totalPrice;
  }

  public void setTotalPrice(@Nullable String totalPrice) {
    this.totalPrice = totalPrice;
  }

  @DynamoDbAttribute(LINES)
  public String getLines() {
    return lines;
  }

  public void setLines(@Nullable String lines) {
    this.lines = lines;
  }

  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(CREATED_AT)
  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(@Nullable Instant createdAt) {
    this.createdAt = createdAt;
  }

  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(UPDATED_AT)
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(@Nullable Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public static String formatPk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatSk(String orderId) {
    if (!orderId.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException("order id must be a positive decimal number: " + orderId);
    }
    return ORDER_PREFIX + String.format(Locale.ROOT, "%020d", Long.parseLong(orderId));
  }

  public static OrderItem create(
      String user,
      String orderId,
      String status,
      @Nullable String fetchtcgStatus,
      @Nullable String fetchtcgCurrentAction,
      @Nullable String deliveryMode,
      @Nullable String buyerName,
      @Nullable BuyerAddress buyerAddress,
      @Nullable String postageOption,
      @Nullable String totalPrice,
      @Nullable String lines,
      Instant createdAt) {
    var item = new OrderItem();
    item.setPk(formatPk(user));
    item.setSk(formatSk(orderId));
    item.setOrderId(orderId);
    item.setStatus(status);
    item.setFetchtcgStatus(fetchtcgStatus);
    item.setFetchtcgCurrentAction(fetchtcgCurrentAction);
    item.setDeliveryMode(deliveryMode);
    item.setBuyerName(buyerName);
    item.setBuyerAddress(buyerAddress);
    item.setPostageOption(postageOption);
    item.setTotalPrice(totalPrice);
    item.setLines(lines);
    item.setCreatedAt(createdAt);
    item.setUpdatedAt(createdAt);
    return item;
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

    @DynamoDbAttribute(LINE1)
    public String getLine1() {
      return line1;
    }

    public void setLine1(@Nullable String line1) {
      this.line1 = line1;
    }

    @DynamoDbAttribute(LINE2)
    public String getLine2() {
      return line2;
    }

    public void setLine2(@Nullable String line2) {
      this.line2 = line2;
    }

    @DynamoDbAttribute(SUBURB)
    public String getSuburb() {
      return suburb;
    }

    public void setSuburb(@Nullable String suburb) {
      this.suburb = suburb;
    }

    @DynamoDbAttribute(CITY)
    public String getCity() {
      return city;
    }

    public void setCity(@Nullable String city) {
      this.city = city;
    }

    @DynamoDbAttribute(POST_CODE)
    public String getPostCode() {
      return postCode;
    }

    public void setPostCode(@Nullable String postCode) {
      this.postCode = postCode;
    }

    @DynamoDbAttribute(COUNTRY)
    public String getCountry() {
      return country;
    }

    public void setCountry(@Nullable String country) {
      this.country = country;
    }

    public static BuyerAddress create(
        @Nullable String line1,
        @Nullable String line2,
        @Nullable String suburb,
        @Nullable String city,
        @Nullable String postCode,
        @Nullable String country) {
      var address = new BuyerAddress();
      address.setLine1(line1);
      address.setLine2(line2);
      address.setSuburb(suburb);
      address.setCity(city);
      address.setPostCode(postCode);
      address.setCountry(country);
      return address;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      BuyerAddress that = (BuyerAddress) o;
      return Objects.equals(line1, that.line1)
          && Objects.equals(line2, that.line2)
          && Objects.equals(suburb, that.suburb)
          && Objects.equals(city, that.city)
          && Objects.equals(postCode, that.postCode)
          && Objects.equals(country, that.country);
    }

    @Override
    public int hashCode() {
      return Objects.hash(line1, line2, suburb, city, postCode, country);
    }
  }
}
