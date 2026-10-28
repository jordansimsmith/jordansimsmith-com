package com.jordansimsmith.tcginventory.pricing.server;

import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class PricingItem {
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String USER = "user";
  public static final String PRICE_FLOOR = "price_floor";

  private String pk;
  private String sk;
  private String user;
  private String priceFloor;

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

  @DynamoDbAttribute(USER)
  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
  }

  @DynamoDbAttribute(PRICE_FLOOR)
  @Nullable
  public String getPriceFloor() {
    return priceFloor;
  }

  public void setPriceFloor(@Nullable String priceFloor) {
    this.priceFloor = priceFloor;
  }

  public static String formatPk(String user) {
    return "USER#" + user;
  }

  public static String formatSk() {
    return "PRICING";
  }

  public static PricingItem create(String user, @Nullable String priceFloor) {
    var item = new PricingItem();
    item.setPk(formatPk(user));
    item.setSk(formatSk());
    item.setUser(user);
    item.setPriceFloor(priceFloor);
    return item;
  }
}
