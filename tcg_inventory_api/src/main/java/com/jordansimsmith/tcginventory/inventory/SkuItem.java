package com.jordansimsmith.tcginventory.inventory;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import java.time.Instant;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class SkuItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String SKU_PREFIX = "SKU" + DELIMITER;
  public static final String DIRTY_SUFFIX = "DIRTY";
  public static final String SKUS_SUFFIX = "SKUS";
  public static final String NAME_PREFIX = "NAME" + DELIMITER;
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String GSI1PK = "gsi1pk";
  public static final String GSI1SK = "gsi1sk";
  public static final String GSI2PK = "gsi2pk";
  public static final String GSI2SK = "gsi2sk";
  public static final String SKU_ID = "sku_id";
  public static final String SCRYFALL_ID = "scryfall_id";
  public static final String FINISH = "finish";
  public static final String CONDITION = "condition";
  public static final String NAME = "name";
  public static final String SET_CODE = "set_code";
  public static final String SET_NAME = "set_name";
  public static final String COLLECTOR_NUMBER = "collector_number";
  public static final String FETCHTCG_CARD_ID = "fetchtcg_card_id";
  public static final String FETCHTCG_SET_ID = "fetchtcg_set_id";
  public static final String VERSION = "version";
  public static final String DIRTY = "dirty";
  public static final String FETCHTCG_LISTING_ID = "fetchtcg_listing_id";
  public static final String LAST_PUBLISHED_QUANTITY = "last_published_quantity";
  public static final String LAST_PUBLISHED_PRICE = "last_published_price";
  public static final String LAST_PUBLISHED_AT = "last_published_at";
  public static final String SUGGESTED_PRICE = "suggested_price";

  private String pk;
  private String sk;
  private String gsi1pk;
  private String gsi1sk;
  private String gsi2pk;
  private String gsi2sk;
  private String skuId;
  private String scryfallId;
  private String finish;
  private String condition;
  private String name;
  private String setCode;
  private String setName;
  private String collectorNumber;
  private String fetchtcgCardId;
  private Integer fetchtcgSetId;
  private Integer version;
  private Boolean dirty;
  private Integer fetchtcgListingId;
  private Integer lastPublishedQuantity;
  private String lastPublishedPrice;
  private Instant lastPublishedAt;
  private String suggestedPrice;

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

  @DynamoDbSecondaryPartitionKey(indexNames = TcgInventoryTable.GSI1_NAME)
  @DynamoDbAttribute(GSI1PK)
  public String getGsi1pk() {
    return gsi1pk;
  }

  public void setGsi1pk(@Nullable String gsi1pk) {
    this.gsi1pk = gsi1pk;
  }

  @DynamoDbSecondarySortKey(indexNames = TcgInventoryTable.GSI1_NAME)
  @DynamoDbAttribute(GSI1SK)
  public String getGsi1sk() {
    return gsi1sk;
  }

  public void setGsi1sk(@Nullable String gsi1sk) {
    this.gsi1sk = gsi1sk;
  }

  @DynamoDbSecondaryPartitionKey(indexNames = TcgInventoryTable.GSI2_NAME)
  @DynamoDbAttribute(GSI2PK)
  public String getGsi2pk() {
    return gsi2pk;
  }

  public void setGsi2pk(@Nullable String gsi2pk) {
    this.gsi2pk = gsi2pk;
  }

  @DynamoDbSecondarySortKey(indexNames = TcgInventoryTable.GSI2_NAME)
  @DynamoDbAttribute(GSI2SK)
  public String getGsi2sk() {
    return gsi2sk;
  }

  public void setGsi2sk(@Nullable String gsi2sk) {
    this.gsi2sk = gsi2sk;
  }

  @DynamoDbAttribute(SKU_ID)
  public String getSkuId() {
    return skuId;
  }

  public void setSkuId(@Nullable String skuId) {
    this.skuId = skuId;
  }

  @DynamoDbAttribute(SCRYFALL_ID)
  public String getScryfallId() {
    return scryfallId;
  }

  public void setScryfallId(@Nullable String scryfallId) {
    this.scryfallId = scryfallId;
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

  @DynamoDbAttribute(FETCHTCG_CARD_ID)
  public String getFetchtcgCardId() {
    return fetchtcgCardId;
  }

  public void setFetchtcgCardId(@Nullable String fetchtcgCardId) {
    this.fetchtcgCardId = fetchtcgCardId;
  }

  @DynamoDbAttribute(FETCHTCG_SET_ID)
  public Integer getFetchtcgSetId() {
    return fetchtcgSetId;
  }

  public void setFetchtcgSetId(@Nullable Integer fetchtcgSetId) {
    this.fetchtcgSetId = fetchtcgSetId;
  }

  @DynamoDbAttribute(VERSION)
  public Integer getVersion() {
    return version;
  }

  public void setVersion(@Nullable Integer version) {
    this.version = version;
  }

  @DynamoDbAttribute(DIRTY)
  public Boolean getDirty() {
    return dirty;
  }

  public void setDirty(@Nullable Boolean dirty) {
    this.dirty = dirty;
  }

  @DynamoDbAttribute(FETCHTCG_LISTING_ID)
  public Integer getFetchtcgListingId() {
    return fetchtcgListingId;
  }

  public void setFetchtcgListingId(@Nullable Integer fetchtcgListingId) {
    this.fetchtcgListingId = fetchtcgListingId;
  }

  @DynamoDbAttribute(LAST_PUBLISHED_QUANTITY)
  public Integer getLastPublishedQuantity() {
    return lastPublishedQuantity;
  }

  public void setLastPublishedQuantity(@Nullable Integer lastPublishedQuantity) {
    this.lastPublishedQuantity = lastPublishedQuantity;
  }

  @DynamoDbAttribute(LAST_PUBLISHED_PRICE)
  public String getLastPublishedPrice() {
    return lastPublishedPrice;
  }

  public void setLastPublishedPrice(@Nullable String lastPublishedPrice) {
    this.lastPublishedPrice = lastPublishedPrice;
  }

  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(LAST_PUBLISHED_AT)
  public Instant getLastPublishedAt() {
    return lastPublishedAt;
  }

  public void setLastPublishedAt(@Nullable Instant lastPublishedAt) {
    this.lastPublishedAt = lastPublishedAt;
  }

  @DynamoDbAttribute(SUGGESTED_PRICE)
  public String getSuggestedPrice() {
    return suggestedPrice;
  }

  public void setSuggestedPrice(@Nullable String suggestedPrice) {
    this.suggestedPrice = suggestedPrice;
  }

  public static String formatPk(String user, String skuId) {
    return USER_PREFIX + user + DELIMITER + SKU_PREFIX + skuId;
  }

  public static String formatUserPk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatSk() {
    return "SKU";
  }

  public static String formatGsi1pk(String user) {
    return USER_PREFIX + user + DELIMITER + DIRTY_SUFFIX;
  }

  public static String formatGsi1sk(String skuId) {
    return SKU_PREFIX + skuId;
  }

  public static String formatGsi2pk(String user) {
    return USER_PREFIX + user + DELIMITER + SKUS_SUFFIX;
  }

  public static String formatGsi2sk(String normalizedName, String skuId) {
    return NAME_PREFIX + normalizedName + DELIMITER + skuId;
  }

  public static SkuItem create(
      String user,
      String skuId,
      String scryfallId,
      String finish,
      String condition,
      String name,
      String setCode,
      String setName,
      String collectorNumber,
      @Nullable String fetchtcgCardId,
      @Nullable String suggestedPrice) {
    var item = new SkuItem();
    item.setPk(formatPk(user, skuId));
    item.setSk(formatSk());
    item.setSkuId(skuId);
    item.setScryfallId(scryfallId);
    item.setFinish(finish);
    item.setCondition(condition);
    item.setName(name);
    item.setSetCode(setCode);
    item.setSetName(setName);
    item.setCollectorNumber(collectorNumber);
    item.setFetchtcgCardId(fetchtcgCardId);
    item.setSuggestedPrice(suggestedPrice);
    item.setVersion(1);
    item.setDirty(true);
    item.setGsi1pk(formatGsi1pk(user));
    item.setGsi1sk(formatGsi1sk(skuId));
    item.setGsi2pk(formatGsi2pk(user));
    item.setGsi2sk(formatGsi2sk(name.toLowerCase(), skuId));
    return item;
  }
}
