package com.jordansimsmith.tcginventory.orders.server;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class OrderSettingsItem {
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String USER = "user";
  public static final String TRACK_ORDERS_AFTER = "track_orders_after";

  private String pk;
  private String sk;
  private String user;
  private Instant trackOrdersAfter;

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

  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(TRACK_ORDERS_AFTER)
  @Nullable
  public Instant getTrackOrdersAfter() {
    return trackOrdersAfter;
  }

  public void setTrackOrdersAfter(@Nullable Instant trackOrdersAfter) {
    this.trackOrdersAfter = trackOrdersAfter;
  }

  public static String formatPk(String user) {
    return "USER#" + user;
  }

  public static String formatSk() {
    return "ORDER_SETTINGS";
  }

  public static OrderSettingsItem create(String user, Instant trackOrdersAfter) {
    var item = new OrderSettingsItem();
    item.setPk(formatPk(user));
    item.setSk(formatSk());
    item.setUser(user);
    item.setTrackOrdersAfter(trackOrdersAfter);
    return item;
  }
}
