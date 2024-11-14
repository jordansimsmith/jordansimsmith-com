package com.jordansimsmith.subfootballtracker;

import java.util.Objects;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class SubfootballTrackerItem {
  public enum Page {
    REGISTRATION
  }

  public static final String DELIMITER = "#";
  public static final String PAGE_PREFIX = "PAGE" + DELIMITER;
  public static final String TIMESTAMP_PREFIX = "TIMESTAMP" + DELIMITER;

  private static final String PK = "pk";
  private static final String SK = "sk";
  private static final String PAGE = "page";
  private static final String TIMESTAMP = "timestamp";
  private static final String CONTENT = "content";
  private static final String VERSION = "version";

  private String pk;
  private String sk;
  private Page page;
  private Long timestamp;
  private String content;
  private Long version;

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

  @DynamoDbAttribute(PAGE)
  public Page getPage() {
    return page;
  }

  public void setPage(Page page) {
    this.page = page;
  }

  @DynamoDbAttribute(TIMESTAMP)
  public Long getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Long timestamp) {
    this.timestamp = timestamp;
  }

  @DynamoDbAttribute(CONTENT)
  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  @DynamoDbVersionAttribute
  @DynamoDbAttribute(VERSION)
  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  @Override
  public String toString() {
    return "SubfootballTrackerItem{"
        + "pk='"
        + pk
        + '\''
        + ", sk='"
        + sk
        + '\''
        + ", page='"
        + page
        + '\''
        + ", timestamp="
        + timestamp
        + ", content='"
        + content
        + '\''
        + ", version="
        + version
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    SubfootballTrackerItem that = (SubfootballTrackerItem) o;
    return Objects.equals(pk, that.pk)
        && Objects.equals(sk, that.sk)
        && Objects.equals(page, that.page)
        && Objects.equals(timestamp, that.timestamp)
        && Objects.equals(content, that.content)
        && Objects.equals(version, that.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(pk, sk, page, timestamp, content, version);
  }

  public static String formatPk(Page page) {
    return PAGE_PREFIX + page;
  }

  public static String formatSk(long timestamp) {
    return TIMESTAMP_PREFIX + timestamp;
  }

  public static SubfootballTrackerItem create(Page page, long timestamp, String content) {
    var subfootballTrackerItem = new SubfootballTrackerItem();
    subfootballTrackerItem.setPk(formatPk(page));
    subfootballTrackerItem.setSk(formatSk(timestamp));
    subfootballTrackerItem.setContent(content);
    subfootballTrackerItem.setTimestamp(timestamp);
    subfootballTrackerItem.setPage(page);
    return subfootballTrackerItem;
  }
}
