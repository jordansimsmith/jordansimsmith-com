package com.jordansimsmith.tcginventory;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class UnitItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String SKU_PREFIX = "SKU" + DELIMITER;
  public static final String UNIT_PREFIX = "UNIT" + DELIMITER;
  public static final String UNITS_SUFFIX = "UNITS";
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String GSI3PK = "gsi3pk";
  public static final String SEQUENCE_NUMBER = "sequence_number";
  public static final String STATUS = "status";
  public static final String IMPORT_ID = "import_id";
  public static final String ORDER_ID = "order_id";
  public static final String CREATED_AT = "created_at";
  public static final String UPDATED_AT = "updated_at";
  public static final String PHOTOS = "photos";

  private String pk;
  private String sk;
  private String gsi3pk;
  private Integer sequenceNumber;
  private String status;
  private String importId;
  private String orderId;
  private Instant createdAt;
  private Instant updatedAt;
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

  @DynamoDbSecondaryPartitionKey(indexNames = TcgInventoryTable.GSI3_NAME)
  @DynamoDbAttribute(GSI3PK)
  public String getGsi3pk() {
    return gsi3pk;
  }

  public void setGsi3pk(@Nullable String gsi3pk) {
    this.gsi3pk = gsi3pk;
  }

  @DynamoDbSecondarySortKey(indexNames = TcgInventoryTable.GSI3_NAME)
  @DynamoDbAttribute(SEQUENCE_NUMBER)
  public Integer getSequenceNumber() {
    return sequenceNumber;
  }

  public void setSequenceNumber(@Nullable Integer sequenceNumber) {
    this.sequenceNumber = sequenceNumber;
  }

  @DynamoDbAttribute(STATUS)
  public String getStatus() {
    return status;
  }

  public void setStatus(@Nullable String status) {
    this.status = status;
  }

  @DynamoDbAttribute(IMPORT_ID)
  public String getImportId() {
    return importId;
  }

  public void setImportId(@Nullable String importId) {
    this.importId = importId;
  }

  @DynamoDbAttribute(ORDER_ID)
  public String getOrderId() {
    return orderId;
  }

  public void setOrderId(@Nullable String orderId) {
    this.orderId = orderId;
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

  @DynamoDbAttribute(PHOTOS)
  public List<Photo> getPhotos() {
    return photos;
  }

  public void setPhotos(@Nullable List<Photo> photos) {
    this.photos = photos;
  }

  public static String formatPk(String user, String skuId) {
    return USER_PREFIX + user + DELIMITER + SKU_PREFIX + skuId;
  }

  public static String formatSk(int sequenceNumber) {
    return UNIT_PREFIX + String.format("%010d", sequenceNumber);
  }

  public static String formatGsi3pk(String user) {
    return USER_PREFIX + user + DELIMITER + UNITS_SUFFIX;
  }

  public static UnitItem create(
      String user,
      String skuId,
      int sequenceNumber,
      String status,
      String importId,
      Instant createdAt) {
    var item = new UnitItem();
    item.setPk(formatPk(user, skuId));
    item.setSk(formatSk(sequenceNumber));
    item.setGsi3pk(formatGsi3pk(user));
    item.setSequenceNumber(sequenceNumber);
    item.setStatus(status);
    item.setImportId(importId);
    item.setCreatedAt(createdAt);
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
