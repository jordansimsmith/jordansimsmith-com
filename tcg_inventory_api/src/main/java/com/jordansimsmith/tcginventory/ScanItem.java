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
public class ScanItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String SCAN_PREFIX = "SCAN" + DELIMITER;
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String SCAN_ID = "scan_id";
  public static final String STATUS = "status";
  public static final String CONDITION = "condition";
  public static final String FINISH = "finish";
  public static final String ROW_COUNT = "row_count";
  public static final String IMPORT_ID = "import_id";
  public static final String ERROR = "error";
  public static final String CATALOG_VERSION = "catalog_version";
  public static final String CREATED_AT = "created_at";
  public static final String UPDATED_AT = "updated_at";

  private String pk;
  private String sk;
  private String scanId;
  private String status;
  private String condition;
  private String finish;
  private Integer rowCount;
  private String importId;
  private String error;
  private Integer catalogVersion;
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

  @DynamoDbAttribute(SCAN_ID)
  public String getScanId() {
    return scanId;
  }

  public void setScanId(@Nullable String scanId) {
    this.scanId = scanId;
  }

  @DynamoDbAttribute(STATUS)
  public String getStatus() {
    return status;
  }

  public void setStatus(@Nullable String status) {
    this.status = status;
  }

  @DynamoDbAttribute(CONDITION)
  public String getCondition() {
    return condition;
  }

  public void setCondition(@Nullable String condition) {
    this.condition = condition;
  }

  @DynamoDbAttribute(FINISH)
  public String getFinish() {
    return finish;
  }

  public void setFinish(@Nullable String finish) {
    this.finish = finish;
  }

  @DynamoDbAttribute(ROW_COUNT)
  public Integer getRowCount() {
    return rowCount;
  }

  public void setRowCount(@Nullable Integer rowCount) {
    this.rowCount = rowCount;
  }

  @DynamoDbAttribute(IMPORT_ID)
  public String getImportId() {
    return importId;
  }

  public void setImportId(@Nullable String importId) {
    this.importId = importId;
  }

  @DynamoDbAttribute(ERROR)
  public String getError() {
    return error;
  }

  public void setError(@Nullable String error) {
    this.error = error;
  }

  @DynamoDbAttribute(CATALOG_VERSION)
  public Integer getCatalogVersion() {
    return catalogVersion;
  }

  public void setCatalogVersion(@Nullable Integer catalogVersion) {
    this.catalogVersion = catalogVersion;
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

  public static String formatSk(String scanId) {
    return SCAN_PREFIX + scanId;
  }

  public static ScanItem create(
      String user,
      String scanId,
      String condition,
      String finish,
      int rowCount,
      Instant createdAt) {
    var item = new ScanItem();
    item.setPk(formatPk(user));
    item.setSk(formatSk(scanId));
    item.setScanId(scanId);
    item.setStatus("uploading");
    item.setCondition(condition);
    item.setFinish(finish);
    item.setRowCount(rowCount);
    item.setCreatedAt(createdAt);
    item.setUpdatedAt(createdAt);
    return item;
  }
}
