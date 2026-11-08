package com.jordansimsmith.tcginventory.reports.server;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class ReportItem {
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String USER = "user";
  public static final String REPORT = "report";
  public static final String UPDATED_AT = "updated_at";
  public static final String GENERATION_ID = "generation_id";
  public static final String GENERATION_STATUS = "generation_status";
  public static final String GENERATION_STARTED_AT = "generation_started_at";
  public static final String GENERATION_FINISHED_AT = "generation_finished_at";

  private String pk;
  private String sk;
  private String user;
  private String report;
  private Instant updatedAt;
  private String generationId;
  private String generationStatus;
  private Instant generationStartedAt;
  private Instant generationFinishedAt;

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
  @DynamoDbAttribute(REPORT)
  public String getReport() {
    return report;
  }

  public void setReport(@Nullable String value) {
    report = value;
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
  @DynamoDbAttribute(GENERATION_ID)
  public String getGenerationId() {
    return generationId;
  }

  public void setGenerationId(@Nullable String value) {
    generationId = value;
  }

  @Nullable
  @DynamoDbAttribute(GENERATION_STATUS)
  public String getGenerationStatus() {
    return generationStatus;
  }

  public void setGenerationStatus(@Nullable String value) {
    generationStatus = value;
  }

  @Nullable
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(GENERATION_STARTED_AT)
  public Instant getGenerationStartedAt() {
    return generationStartedAt;
  }

  public void setGenerationStartedAt(@Nullable Instant value) {
    generationStartedAt = value;
  }

  @Nullable
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(GENERATION_FINISHED_AT)
  public Instant getGenerationFinishedAt() {
    return generationFinishedAt;
  }

  public void setGenerationFinishedAt(@Nullable Instant value) {
    generationFinishedAt = value;
  }

  public static String formatPk(String user) {
    return "USER#" + user;
  }

  public static String formatSk() {
    return "REPORT";
  }
}
