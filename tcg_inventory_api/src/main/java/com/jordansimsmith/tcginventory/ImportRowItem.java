package com.jordansimsmith.tcginventory;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class ImportRowItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String IMPORT_PREFIX = "IMPORT" + DELIMITER;
  public static final String ROW_PREFIX = "ROW" + DELIMITER;
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String POSITION = "position";
  public static final String NAME = "name";
  public static final String SET_CODE = "set_code";
  public static final String SET_NAME = "set_name";
  public static final String COLLECTOR_NUMBER = "collector_number";
  public static final String FINISH = "finish";
  public static final String CONDITION = "condition";
  public static final String SCRYFALL_ID = "scryfall_id";
  public static final String LANGUAGE = "language";
  public static final String DECISION = "decision";
  public static final String DECISION_REASON = "decision_reason";
  public static final String MARKET_PRICE = "market_price";
  public static final String SUGGESTED_PRICE = "suggested_price";
  public static final String FETCHTCG_CARD_ID = "fetchtcg_card_id";
  public static final String FETCHTCG_SET_ID = "fetchtcg_set_id";
  public static final String SEQUENCE_NUMBER = "sequence_number";
  public static final String PHOTOS = "photos";

  private String pk;
  private String sk;
  private Integer position;
  private String name;
  private String setCode;
  private String setName;
  private String collectorNumber;
  private String finish;
  private String condition;
  private String scryfallId;
  private String language;
  private String decision;
  private String decisionReason;
  private String marketPrice;
  private String suggestedPrice;
  private String fetchtcgCardId;
  private Integer fetchtcgSetId;
  private Integer sequenceNumber;
  private List<Photo> photos;

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

  @DynamoDbAttribute(POSITION)
  public Integer getPosition() {
    return position;
  }

  public void setPosition(@Nullable Integer position) {
    this.position = position;
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

  @DynamoDbAttribute(SCRYFALL_ID)
  public String getScryfallId() {
    return scryfallId;
  }

  public void setScryfallId(@Nullable String scryfallId) {
    this.scryfallId = scryfallId;
  }

  @DynamoDbAttribute(LANGUAGE)
  public String getLanguage() {
    return language;
  }

  public void setLanguage(@Nullable String language) {
    this.language = language;
  }

  @DynamoDbAttribute(DECISION)
  public String getDecision() {
    return decision;
  }

  public void setDecision(@Nullable String decision) {
    this.decision = decision;
  }

  @DynamoDbAttribute(DECISION_REASON)
  public String getDecisionReason() {
    return decisionReason;
  }

  public void setDecisionReason(@Nullable String decisionReason) {
    this.decisionReason = decisionReason;
  }

  @DynamoDbAttribute(MARKET_PRICE)
  public String getMarketPrice() {
    return marketPrice;
  }

  public void setMarketPrice(@Nullable String marketPrice) {
    this.marketPrice = marketPrice;
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
  public Integer getFetchtcgSetId() {
    return fetchtcgSetId;
  }

  public void setFetchtcgSetId(@Nullable Integer fetchtcgSetId) {
    this.fetchtcgSetId = fetchtcgSetId;
  }

  @DynamoDbAttribute(SEQUENCE_NUMBER)
  public Integer getSequenceNumber() {
    return sequenceNumber;
  }

  public void setSequenceNumber(@Nullable Integer sequenceNumber) {
    this.sequenceNumber = sequenceNumber;
  }

  @DynamoDbAttribute(PHOTOS)
  public List<Photo> getPhotos() {
    return photos;
  }

  public void setPhotos(@Nullable List<Photo> photos) {
    this.photos = photos;
  }

  public static String formatPk(String user, String importId) {
    return USER_PREFIX + user + DELIMITER + IMPORT_PREFIX + importId;
  }

  public static String formatSk(int position) {
    return ROW_PREFIX + String.format("%010d", position);
  }

  public static ImportRowItem create(
      String user,
      String importId,
      int position,
      String name,
      String setCode,
      String setName,
      String collectorNumber,
      String finish,
      String condition,
      String scryfallId,
      String language) {
    var item = new ImportRowItem();
    item.setPk(formatPk(user, importId));
    item.setSk(formatSk(position));
    item.setPosition(position);
    item.setName(name);
    item.setSetCode(setCode);
    item.setSetName(setName);
    item.setCollectorNumber(collectorNumber);
    item.setFinish(finish);
    item.setCondition(condition);
    item.setScryfallId(scryfallId);
    item.setLanguage(language);
    return item;
  }

  @DynamoDbBean
  public static class Photo {
    public static final String PHOTO_ID = "photo_id";
    public static final String FETCHTCG_URL = "fetchtcg_url";

    private String photoId;
    private String fetchtcgUrl;

    @DynamoDbAttribute(PHOTO_ID)
    public String getPhotoId() {
      return photoId;
    }

    public void setPhotoId(String photoId) {
      this.photoId = photoId;
    }

    @DynamoDbAttribute(FETCHTCG_URL)
    public String getFetchtcgUrl() {
      return fetchtcgUrl;
    }

    public void setFetchtcgUrl(@Nullable String fetchtcgUrl) {
      this.fetchtcgUrl = fetchtcgUrl;
    }

    public static Photo create(String photoId, @Nullable String fetchtcgUrl) {
      var photo = new Photo();
      photo.setPhotoId(photoId);
      photo.setFetchtcgUrl(fetchtcgUrl);
      return photo;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Photo that = (Photo) o;
      return Objects.equals(photoId, that.photoId) && Objects.equals(fetchtcgUrl, that.fetchtcgUrl);
    }

    @Override
    public int hashCode() {
      return Objects.hash(photoId, fetchtcgUrl);
    }
  }
}
