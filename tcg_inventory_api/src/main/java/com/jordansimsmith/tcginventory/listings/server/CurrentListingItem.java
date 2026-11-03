package com.jordansimsmith.tcginventory.listings.server;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class CurrentListingItem {
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String USER = "user";
  public static final String SKU_ID = "sku_id";
  public static final String LISTING_ID = "listing_id";

  private String pk;
  private String sk;
  private String user;
  private String skuId;
  private Integer listingId;

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

  @DynamoDbAttribute(SKU_ID)
  public String getSkuId() {
    return skuId;
  }

  public void setSkuId(String skuId) {
    this.skuId = skuId;
  }

  @DynamoDbAttribute(LISTING_ID)
  public Integer getListingId() {
    return listingId;
  }

  public void setListingId(Integer listingId) {
    this.listingId = listingId;
  }

  public static String formatPk(String user) {
    return "USER#" + user + "#LISTINGS";
  }

  public static String formatSk(String skuId) {
    return "SKU#" + skuId;
  }

  public static CurrentListingItem create(String user, String skuId, int listingId) {
    var item = new CurrentListingItem();
    item.setPk(formatPk(user));
    item.setSk(formatSk(skuId));
    item.setUser(user);
    item.setSkuId(skuId);
    item.setListingId(listingId);
    return item;
  }
}
