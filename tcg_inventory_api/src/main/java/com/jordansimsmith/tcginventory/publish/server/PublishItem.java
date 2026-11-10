package com.jordansimsmith.tcginventory.publish.server;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class PublishItem {
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String USER = "user";
  public static final String PUBLISH_ID = "publish_id";
  public static final String STATUS = "status";
  public static final String PUBLISHED_SKU_COUNT = "published_sku_count";
  public static final String STARTED_AT = "started_at";
  public static final String UPDATED_AT = "updated_at";
  public static final String FINISHED_AT = "finished_at";

  private String pk;
  private String sk;
  private String user;
  private String publishId;
  private String status;
  private Integer publishedSkuCount;
  private Instant startedAt;
  private Instant updatedAt;
  private Instant finishedAt;

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

  @Nullable
  @DynamoDbAttribute(USER)
  public String getUser() {
    return user;
  }

  public void setUser(@Nullable String value) {
    user = value;
  }

  @Nullable
  @DynamoDbAttribute(PUBLISH_ID)
  public String getPublishId() {
    return publishId;
  }

  public void setPublishId(@Nullable String value) {
    publishId = value;
  }

  @Nullable
  @DynamoDbAttribute(STATUS)
  public String getStatus() {
    return status;
  }

  public void setStatus(@Nullable String value) {
    status = value;
  }

  @Nullable
  @DynamoDbAttribute(PUBLISHED_SKU_COUNT)
  public Integer getPublishedSkuCount() {
    return publishedSkuCount;
  }

  public void setPublishedSkuCount(@Nullable Integer value) {
    publishedSkuCount = value;
  }

  @Nullable
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(STARTED_AT)
  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(@Nullable Instant value) {
    startedAt = value;
  }

  @Nullable
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(UPDATED_AT)
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(@Nullable Instant value) {
    updatedAt = value;
  }

  @Nullable
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(FINISHED_AT)
  public Instant getFinishedAt() {
    return finishedAt;
  }

  public void setFinishedAt(@Nullable Instant value) {
    finishedAt = value;
  }

  public static String formatPk(String user) {
    return "USER#" + user;
  }

  public static String formatSk() {
    return "PUBLISH";
  }
}
