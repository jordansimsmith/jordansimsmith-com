package com.jordansimsmith.tcginventory;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class JobItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String JOB_PREFIX = "JOB" + DELIMITER;
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String JOB_ID = "job_id";
  public static final String JOB_TYPE = "job_type";
  public static final String STATUS = "status";
  public static final String IMPORT_ID = "import_id";
  public static final String CONTINUATION = "continuation";
  public static final String PROCESSED_COUNT = "processed_count";
  public static final String ERROR = "error";
  public static final String CREATED_AT = "created_at";
  public static final String UPDATED_AT = "updated_at";

  private String pk;
  private String sk;
  private String jobId;
  private String jobType;
  private String status;
  private String importId;
  private Integer continuation;
  private Integer processedCount;
  private String error;
  private Instant createdAt;
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

  @DynamoDbAttribute(JOB_ID)
  public String getJobId() {
    return jobId;
  }

  public void setJobId(@Nullable String jobId) {
    this.jobId = jobId;
  }

  @DynamoDbAttribute(JOB_TYPE)
  public String getJobType() {
    return jobType;
  }

  public void setJobType(@Nullable String jobType) {
    this.jobType = jobType;
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

  @DynamoDbAttribute(CONTINUATION)
  public Integer getContinuation() {
    return continuation;
  }

  public void setContinuation(@Nullable Integer continuation) {
    this.continuation = continuation;
  }

  @DynamoDbAttribute(PROCESSED_COUNT)
  public Integer getProcessedCount() {
    return processedCount;
  }

  public void setProcessedCount(@Nullable Integer processedCount) {
    this.processedCount = processedCount;
  }

  @DynamoDbAttribute(ERROR)
  public String getError() {
    return error;
  }

  public void setError(@Nullable String error) {
    this.error = error;
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

  public static String formatPk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatSk(String jobId) {
    return JOB_PREFIX + jobId;
  }

  public static JobItem create(
      String user, String jobId, String jobType, @Nullable String importId, Instant createdAt) {
    var item = new JobItem();
    item.setPk(formatPk(user));
    item.setSk(formatSk(jobId));
    item.setJobId(jobId);
    item.setJobType(jobType);
    item.setStatus("queued");
    item.setImportId(importId);
    item.setCreatedAt(createdAt);
    return item;
  }
}
