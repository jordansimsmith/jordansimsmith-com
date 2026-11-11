package com.jordansimsmith.tcginventory.imports.server;

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
  public static final String USER_PREFIX = "USER#";
  public static final String IMPORT_PREFIX = "IMPORT#";
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String IMPORT_ID = "import_id";
  public static final String GAME = "game";
  public static final String FILENAME = "filename";
  public static final String STATUS = "status";
  public static final String ROW_COUNT = "row_count";
  public static final String CREATED_AT = "created_at";

  private String pk;
  private String sk;
  private String importId;
  private String game;
  private String filename;
  private String status;
  private Integer rowCount;
  private Instant createdAt;

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

  @DynamoDbAttribute(IMPORT_ID)
  public String getImportId() {
    return importId;
  }

  public void setImportId(@Nullable String value) {
    importId = value;
  }

  @DynamoDbAttribute(GAME)
  public String getGame() {
    return game;
  }

  public void setGame(@Nullable String value) {
    game = value;
  }

  @Nullable
  @DynamoDbAttribute(FILENAME)
  public String getFilename() {
    return filename;
  }

  public void setFilename(@Nullable String value) {
    filename = value;
  }

  @DynamoDbAttribute(STATUS)
  public String getStatus() {
    return status;
  }

  public void setStatus(@Nullable String value) {
    status = value;
  }

  @DynamoDbAttribute(ROW_COUNT)
  public Integer getRowCount() {
    return rowCount;
  }

  public void setRowCount(@Nullable Integer value) {
    rowCount = value;
  }

  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(CREATED_AT)
  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(@Nullable Instant value) {
    createdAt = value;
  }

  public static String formatPk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatSk(String importId) {
    return IMPORT_PREFIX + importId;
  }
}
