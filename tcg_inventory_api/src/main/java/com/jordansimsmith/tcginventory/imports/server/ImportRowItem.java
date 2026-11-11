package com.jordansimsmith.tcginventory.imports.server;

import java.util.List;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class ImportRowItem {
  public static final String USER_PREFIX = "USER#";
  public static final String IMPORT_PREFIX = "IMPORT#";
  public static final String ROW_PREFIX = "ROW#";
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String POSITION = "position";
  public static final String NAME = "name";
  public static final String SET_CODE = "set_code";
  public static final String SET_NAME = "set_name";
  public static final String COLLECTOR_NUMBER = "collector_number";
  public static final String FINISH = "finish";
  public static final String CONDITION = "condition";
  public static final String EXTERNAL_ID = "external_id";
  public static final String DECISION = "decision";
  public static final String DECISION_REASON = "decision_reason";
  public static final String MARKET_PRICE = "market_price";
  public static final String SUGGESTED_PRICE = "suggested_price";
  public static final String PHOTOS = "photos";
  public static final String SKU_ID = "sku_id";
  public static final String SEQUENCE_NUMBER = "sequence_number";
  public static final String LOCATION = "location";

  private String pk;
  private String sk;
  private Integer position;
  private String name;
  private String setCode;
  private String setName;
  private String collectorNumber;
  private String finish;
  private String condition;
  private String externalId;
  private String decision;
  private String decisionReason;
  private String marketPrice;
  private String suggestedPrice;
  private List<Photo> photos;
  private String skuId;
  private Integer sequenceNumber;
  private String location;

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

  @DynamoDbAttribute(POSITION)
  public Integer getPosition() {
    return position;
  }

  public void setPosition(@Nullable Integer value) {
    position = value;
  }

  @DynamoDbAttribute(NAME)
  public String getName() {
    return name;
  }

  public void setName(@Nullable String value) {
    name = value;
  }

  @DynamoDbAttribute(SET_CODE)
  public String getSetCode() {
    return setCode;
  }

  public void setSetCode(@Nullable String value) {
    setCode = value;
  }

  @DynamoDbAttribute(SET_NAME)
  public String getSetName() {
    return setName;
  }

  public void setSetName(@Nullable String value) {
    setName = value;
  }

  @DynamoDbAttribute(COLLECTOR_NUMBER)
  public String getCollectorNumber() {
    return collectorNumber;
  }

  public void setCollectorNumber(@Nullable String value) {
    collectorNumber = value;
  }

  @DynamoDbAttribute(FINISH)
  public String getFinish() {
    return finish;
  }

  public void setFinish(@Nullable String value) {
    finish = value;
  }

  @DynamoDbAttribute(CONDITION)
  public String getCondition() {
    return condition;
  }

  public void setCondition(@Nullable String value) {
    condition = value;
  }

  @DynamoDbAttribute(EXTERNAL_ID)
  public String getExternalId() {
    return externalId;
  }

  public void setExternalId(@Nullable String value) {
    externalId = value;
  }

  @DynamoDbAttribute(DECISION)
  public String getDecision() {
    return decision;
  }

  public void setDecision(@Nullable String value) {
    decision = value;
  }

  @DynamoDbAttribute(DECISION_REASON)
  public String getDecisionReason() {
    return decisionReason;
  }

  public void setDecisionReason(@Nullable String value) {
    decisionReason = value;
  }

  @DynamoDbAttribute(MARKET_PRICE)
  public String getMarketPrice() {
    return marketPrice;
  }

  public void setMarketPrice(@Nullable String value) {
    marketPrice = value;
  }

  @DynamoDbAttribute(SUGGESTED_PRICE)
  public String getSuggestedPrice() {
    return suggestedPrice;
  }

  public void setSuggestedPrice(@Nullable String value) {
    suggestedPrice = value;
  }

  @DynamoDbAttribute(PHOTOS)
  public List<Photo> getPhotos() {
    return photos;
  }

  public void setPhotos(@Nullable List<Photo> value) {
    photos = value;
  }

  @DynamoDbAttribute(SKU_ID)
  public String getSkuId() {
    return skuId;
  }

  public void setSkuId(@Nullable String value) {
    skuId = value;
  }

  @DynamoDbAttribute(SEQUENCE_NUMBER)
  public Integer getSequenceNumber() {
    return sequenceNumber;
  }

  public void setSequenceNumber(@Nullable Integer value) {
    sequenceNumber = value;
  }

  @DynamoDbAttribute(LOCATION)
  public String getLocation() {
    return location;
  }

  public void setLocation(@Nullable String value) {
    location = value;
  }

  public static String formatPk(String user, String importId) {
    return USER_PREFIX + user + "#" + IMPORT_PREFIX + importId;
  }

  public static String formatSk(int position) {
    return ROW_PREFIX + String.format("%010d", position);
  }

  @DynamoDbBean
  public static class Photo {
    public static final String PHOTO_ID = "photo_id";

    private String photoId;

    @DynamoDbAttribute(PHOTO_ID)
    public String getPhotoId() {
      return photoId;
    }

    public void setPhotoId(@Nullable String value) {
      photoId = value;
    }

    public static Photo create(String photoId) {
      var photo = new Photo();
      photo.setPhotoId(photoId);
      return photo;
    }
  }
}
