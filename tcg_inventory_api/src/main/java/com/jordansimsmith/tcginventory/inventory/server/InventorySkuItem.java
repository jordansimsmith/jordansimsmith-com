package com.jordansimsmith.tcginventory.inventory.server;

import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class InventorySkuItem {
  public static final String GAME = "game";
  public static final String SKU_ID = "sku_id";
  public static final String EXTERNAL_ID = "external_id";
  public static final String NAME = "name";
  public static final String SET_CODE = "set_code";
  public static final String SET_NAME = "set_name";
  public static final String COLLECTOR_NUMBER = "collector_number";
  public static final String FINISH = "finish";
  public static final String CONDITION = "condition";
  public static final String SUGGESTED_PRICE = "suggested_price";
  public static final String FETCHTCG_CARD_ID = "fetchtcg_card_id";
  public static final String FETCHTCG_SET_ID = "fetchtcg_set_id";
  public static final String FETCHTCG_LISTING_ID = "fetchtcg_listing_id";
  public static final String VERSION = "version";
  public static final String GSI1PK = "gsi1pk";
  public static final String GSI1SK = "gsi1sk";
  public static final String GSI2PK = "gsi2pk";
  public static final String GSI2SK = "gsi2sk";
  private String pk;
  private String sk;
  private String game;
  private String skuId;
  private String externalId;
  private String name;
  private String setCode;
  private String setName;
  private String collectorNumber;
  private String finish;
  private String condition;
  private String suggestedPrice;
  private String fetchtcgCardId;
  private int fetchtcgSetId;
  private Integer fetchtcgListingId;
  private Integer version;
  private String gsi1pk;
  private String gsi1sk;
  private String gsi2pk;
  private String gsi2sk;

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

  @DynamoDbAttribute(GAME)
  public String getGame() {
    return game;
  }

  public void setGame(@Nullable String game) {
    this.game = game;
  }

  @DynamoDbAttribute(SKU_ID)
  public String getSkuId() {
    return skuId;
  }

  public void setSkuId(@Nullable String skuId) {
    this.skuId = skuId;
  }

  @DynamoDbAttribute(EXTERNAL_ID)
  public String getExternalId() {
    return externalId;
  }

  public void setExternalId(@Nullable String externalId) {
    this.externalId = externalId;
  }

  @DynamoDbAttribute(NAME)
  public String getName() {
    return name;
  }

  public void setName(@Nullable String name) {
    this.name = name;
  }

  @DynamoDbAttribute(SET_CODE)
  public String getSetCode() {
    return setCode;
  }

  public void setSetCode(@Nullable String setCode) {
    this.setCode = setCode;
  }

  @DynamoDbAttribute(SET_NAME)
  public String getSetName() {
    return setName;
  }

  public void setSetName(@Nullable String setName) {
    this.setName = setName;
  }

  @DynamoDbAttribute(COLLECTOR_NUMBER)
  public String getCollectorNumber() {
    return collectorNumber;
  }

  public void setCollectorNumber(@Nullable String collectorNumber) {
    this.collectorNumber = collectorNumber;
  }

  @DynamoDbAttribute(FINISH)
  public String getFinish() {
    return finish;
  }

  public void setFinish(@Nullable String finish) {
    this.finish = finish;
  }

  @DynamoDbAttribute(CONDITION)
  public String getCondition() {
    return condition;
  }

  public void setCondition(@Nullable String condition) {
    this.condition = condition;
  }

  @DynamoDbAttribute(SUGGESTED_PRICE)
  public String getSuggestedPrice() {
    return suggestedPrice;
  }

  public void setSuggestedPrice(@Nullable String suggestedPrice) {
    this.suggestedPrice = suggestedPrice;
  }

  @DynamoDbAttribute(FETCHTCG_CARD_ID)
  public String getFetchtcgCardId() {
    return fetchtcgCardId;
  }

  public void setFetchtcgCardId(@Nullable String fetchtcgCardId) {
    this.fetchtcgCardId = fetchtcgCardId;
  }

  @DynamoDbAttribute(FETCHTCG_SET_ID)
  public int getFetchtcgSetId() {
    return fetchtcgSetId;
  }

  public void setFetchtcgSetId(int fetchtcgSetId) {
    this.fetchtcgSetId = fetchtcgSetId;
  }

  @DynamoDbAttribute(FETCHTCG_LISTING_ID)
  public Integer getFetchtcgListingId() {
    return fetchtcgListingId;
  }

  public void setFetchtcgListingId(@Nullable Integer fetchtcgListingId) {
    this.fetchtcgListingId = fetchtcgListingId;
  }

  @DynamoDbAttribute(VERSION)
  public Integer getVersion() {
    return version;
  }

  public void setVersion(@Nullable Integer version) {
    this.version = version;
  }

  @DynamoDbSecondaryPartitionKey(indexNames = "gsi1")
  @DynamoDbAttribute(GSI1PK)
  public String getGsi1pk() {
    return gsi1pk;
  }

  public void setGsi1pk(@Nullable String gsi1pk) {
    this.gsi1pk = gsi1pk;
  }

  @DynamoDbSecondarySortKey(indexNames = "gsi1")
  @DynamoDbAttribute(GSI1SK)
  public String getGsi1sk() {
    return gsi1sk;
  }

  public void setGsi1sk(@Nullable String gsi1sk) {
    this.gsi1sk = gsi1sk;
  }

  @DynamoDbSecondaryPartitionKey(indexNames = "gsi2")
  @DynamoDbAttribute(GSI2PK)
  public String getGsi2pk() {
    return gsi2pk;
  }

  public void setGsi2pk(@Nullable String gsi2pk) {
    this.gsi2pk = gsi2pk;
  }

  @DynamoDbSecondarySortKey(indexNames = "gsi2")
  @DynamoDbAttribute(GSI2SK)
  public String getGsi2sk() {
    return gsi2sk;
  }

  public void setGsi2sk(@Nullable String gsi2sk) {
    this.gsi2sk = gsi2sk;
  }

  public static String formatPk(String user, String skuId) {
    return "USER#" + user + "#SKU#" + skuId;
  }

  public static String formatGsi2pk(String user) {
    return "USER#" + user + "#SKUS";
  }

  public static String formatGsi1pk(String user, boolean dirty) {
    return "USER#" + user + (dirty ? "#DIRTY" : "#CLEAN");
  }

  public static String formatGsi1skPrefix() {
    return "SKU#";
  }

  public static String formatGsi2skPrefix(@Nullable String game, String search) {
    if (game == null) {
      return "GAME#";
    }
    return "GAME#" + game + "#NAME#" + search;
  }
}
