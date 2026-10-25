package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class FetchTcgAccountItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String USER = "user";
  public static final String SELLER_USERNAME = "seller_username";
  public static final String CREDENTIALS_UPDATED_AT = "credentials_updated_at";
  public static final String ACCOUNT_SORT_KEY = "FETCHTCG_ACCOUNT";

  private String pk;
  private String sk;
  private String user;
  private String sellerUsername;
  private Instant credentialsUpdatedAt;

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

  @DynamoDbAttribute(SELLER_USERNAME)
  public String getSellerUsername() {
    return sellerUsername;
  }

  public void setSellerUsername(String sellerUsername) {
    this.sellerUsername = sellerUsername;
  }

  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(CREDENTIALS_UPDATED_AT)
  public Instant getCredentialsUpdatedAt() {
    return credentialsUpdatedAt;
  }

  public void setCredentialsUpdatedAt(Instant credentialsUpdatedAt) {
    this.credentialsUpdatedAt = credentialsUpdatedAt;
  }

  public static String formatPk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatSk() {
    return ACCOUNT_SORT_KEY;
  }

  public static FetchTcgAccountItem create(
      String user, String sellerUsername, Instant credentialsUpdatedAt) {
    var item = new FetchTcgAccountItem();
    item.setPk(formatPk(user));
    item.setSk(formatSk());
    item.setUser(user);
    item.setSellerUsername(sellerUsername);
    item.setCredentialsUpdatedAt(credentialsUpdatedAt);
    return item;
  }
}
