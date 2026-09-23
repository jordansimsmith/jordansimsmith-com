package com.jordansimsmith.tcginventory.reports;

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
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String REPORT = "report";
  public static final String AS_OF_AUDIT_ULID = "as_of_audit_ulid";
  public static final String UPDATED_AT = "updated_at";

  private String pk;
  private String sk;
  private String report;
  private String asOfAuditUlid;
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

  @DynamoDbAttribute(REPORT)
  public String getReport() {
    return report;
  }

  public void setReport(@Nullable String report) {
    this.report = report;
  }

  @DynamoDbAttribute(AS_OF_AUDIT_ULID)
  public String getAsOfAuditUlid() {
    return asOfAuditUlid;
  }

  public void setAsOfAuditUlid(@Nullable String asOfAuditUlid) {
    this.asOfAuditUlid = asOfAuditUlid;
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
    return "REPORT";
  }

  public static ReportItem create(
      String user, String report, @Nullable String asOfAuditUlid, Instant updatedAt) {
    var item = new ReportItem();
    item.setPk(formatPk(user));
    item.setSk(formatSk());
    item.setReport(report);
    item.setAsOfAuditUlid(asOfAuditUlid);
    item.setUpdatedAt(updatedAt);
    return item;
  }
}
