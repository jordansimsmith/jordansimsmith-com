package com.jordansimsmith.tcginventory.settings;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class SettingsItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String TRACK_ORDERS_AFTER = "track_orders_after";
  public static final String UPDATED_AT = "updated_at";

  private String pk;
  private String sk;
  private Instant trackOrdersAfter;
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

  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(TRACK_ORDERS_AFTER)
  public Instant getTrackOrdersAfter() {
    return trackOrdersAfter;
  }

  public void setTrackOrdersAfter(@Nullable Instant trackOrdersAfter) {
    this.trackOrdersAfter = trackOrdersAfter;
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

  public static String formatSk() {
    return "SETTINGS";
  }

  public static SettingsItem create(String user, Instant updatedAt) {
    var item = new SettingsItem();
    item.setPk(formatPk(user));
    item.setSk(formatSk());
    item.setUpdatedAt(updatedAt);
    return item;
  }
}
