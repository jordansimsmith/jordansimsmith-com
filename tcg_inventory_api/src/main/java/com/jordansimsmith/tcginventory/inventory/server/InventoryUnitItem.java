package com.jordansimsmith.tcginventory.inventory.server;

import java.util.List;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class InventoryUnitItem {
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String SEQUENCE_NUMBER = "sequence_number";
  public static final String STATUS = "status";
  public static final String PHOTOS = "photos";
  public static final String UNIT_PREFIX = "UNIT#";

  private String pk;
  private String sk;
  private Integer sequenceNumber;
  private String status;
  private List<PhotoItem> photos;

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

  public void setStatus(String status) {
    this.status = status;
  }

  @DynamoDbAttribute(PHOTOS)
  public List<PhotoItem> getPhotos() {
    return photos;
  }

  public void setPhotos(@Nullable List<PhotoItem> photos) {
    this.photos = photos;
  }

  public static String formatPk(String user, String skuId) {
    return "USER#" + user + "#SKU#" + skuId;
  }

  @DynamoDbBean
  public static class PhotoItem {
    public static final String PHOTO_ID = "photo_id";

    private String photoId;

    @DynamoDbAttribute(PHOTO_ID)
    public String getPhotoId() {
      return photoId;
    }

    public void setPhotoId(String photoId) {
      this.photoId = photoId;
    }
  }
}
