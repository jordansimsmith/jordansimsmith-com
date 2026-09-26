package com.jordansimsmith.tcginventory.imports;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class ImportItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String IMPORT_PREFIX = "IMPORT" + DELIMITER;
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String IMPORT_ID = "import_id";
  public static final String GAME = "game";
  public static final String FILENAME = "filename";
  public static final String STATUS = "status";
  public static final String ROW_COUNT = "row_count";
  public static final String JOB_ID = "job_id";
  public static final String ERROR = "error";
  public static final String CREATED_AT = "created_at";
  public static final String UPDATED_AT = "updated_at";

  private String pk;
  private String sk;
  private String importId;
  private String game;
  private String filename;
  private String status;
  private Integer rowCount;
  private String jobId;
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

  @DynamoDbAttribute(IMPORT_ID)
  public String getImportId() {
    return importId;
  }

  @DynamoDbAttribute(GAME)
  public String getGame() {
    return game;
  }

  public void setGame(@Nullable String game) {
    this.game = game;
  }

  public void setImportId(@Nullable String importId) {
    this.importId = importId;
  }

  @DynamoDbAttribute(FILENAME)
  public String getFilename() {
    return filename;
  }

  public void setFilename(@Nullable String filename) {
    this.filename = filename;
  }

  @DynamoDbAttribute(STATUS)
  public String getStatus() {
    return status;
  }

  public void setStatus(@Nullable String status) {
    this.status = status;
  }

  @DynamoDbAttribute(ROW_COUNT)
  public Integer getRowCount() {
    return rowCount;
  }

  public void setRowCount(@Nullable Integer rowCount) {
    this.rowCount = rowCount;
  }

  @DynamoDbAttribute(JOB_ID)
  public String getJobId() {
    return jobId;
  }

  public void setJobId(@Nullable String jobId) {
    this.jobId = jobId;
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

  public static String formatSk(String importId) {
    return IMPORT_PREFIX + importId;
  }

  public static ImportItem create(
      String user,
      String game,
      String importId,
      String filename,
      int rowCount,
      @Nullable String jobId,
      Instant createdAt) {
    var item = new ImportItem();
    item.setPk(formatPk(user));
    item.setSk(formatSk(importId));
    item.setImportId(importId);
    item.setGame(game);
    item.setFilename(filename);
    item.setStatus("appraising");
    item.setRowCount(rowCount);
    item.setJobId(jobId);
    item.setCreatedAt(createdAt);
    return item;
  }
}
