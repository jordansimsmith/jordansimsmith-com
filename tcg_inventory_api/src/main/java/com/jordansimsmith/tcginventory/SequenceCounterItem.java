package com.jordansimsmith.tcginventory;

import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class SequenceCounterItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String COUNTER_PREFIX = "COUNTER" + DELIMITER;
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String NEXT_SEQUENCE_NUMBER = "next_sequence_number";

  private String pk;
  private String sk;
  private Integer nextSequenceNumber;

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

  @DynamoDbAttribute(NEXT_SEQUENCE_NUMBER)
  public Integer getNextSequenceNumber() {
    return nextSequenceNumber;
  }

  public void setNextSequenceNumber(@Nullable Integer nextSequenceNumber) {
    this.nextSequenceNumber = nextSequenceNumber;
  }

  public static String formatPk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatSk() {
    return COUNTER_PREFIX + "SEQUENCE";
  }
}
