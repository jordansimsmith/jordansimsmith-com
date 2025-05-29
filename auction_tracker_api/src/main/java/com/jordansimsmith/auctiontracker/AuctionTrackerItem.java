package com.jordansimsmith.auctiontracker;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

@DynamoDbBean
public class AuctionTrackerItem {
  public static final String DELIMITER = "#";
  public static final String SEARCH_PREFIX = "SEARCH" + DELIMITER;
  public static final String TIMESTAMP_PREFIX = "TIMESTAMP" + DELIMITER;
  public static final String ITEM_PREFIX = "ITEM" + DELIMITER;

  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String TITLE = "title";
  public static final String TIMESTAMP = "timestamp";
  public static final String URL = "url";
  public static final String TTL = "ttl";
  public static final String VERSION = "version";

  private String pk;
  private String sk;
  private String title;
  private Instant timestamp;
  private String url;
  private Long ttl;
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

  @DynamoDbAttribute(TITLE)
  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  @DynamoDbAttribute(TIMESTAMP)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  @DynamoDbAttribute(URL)
  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  @DynamoDbAttribute(TTL)
  public Long getTtl() {
    return ttl;
  }

  public void setTtl(Long ttl) {
    this.ttl = ttl;
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
    return "AuctionTrackerItem{"
        + "pk='"
        + pk
        + '\''
        + ", sk='"
        + sk
        + '\''
        + ", title='"
        + title
        + '\''
        + ", timestamp='"
        + timestamp
        + '\''
        + ", url='"
        + url
        + '\''
        + ", ttl='"
        + ttl
        + '\''
        + ", version='"
        + version
        + '\''
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    AuctionTrackerItem auctionTrackerItem = (AuctionTrackerItem) o;
    return Objects.equals(pk, auctionTrackerItem.pk)
        && Objects.equals(sk, auctionTrackerItem.sk)
        && Objects.equals(title, auctionTrackerItem.title)
        && Objects.equals(timestamp, auctionTrackerItem.timestamp)
        && Objects.equals(url, auctionTrackerItem.url)
        && Objects.equals(ttl, auctionTrackerItem.ttl)
        && Objects.equals(version, auctionTrackerItem.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(pk, sk, title, timestamp, url, ttl, version);
  }

  public static String formatPk(String searchUrl) {
    return SEARCH_PREFIX + searchUrl;
  }

  public static String formatSk(Instant timestamp, String itemUrl) {
    return TIMESTAMP_PREFIX + timestamp.getEpochSecond() + ITEM_PREFIX + itemUrl;
  }

  public static AuctionTrackerItem create(
      String searchUrl, String itemUrl, String title, Instant timestamp) {
    var auctionTrackerItem = new AuctionTrackerItem();
    auctionTrackerItem.setPk(formatPk(searchUrl));
    auctionTrackerItem.setSk(formatSk(timestamp, itemUrl));
    auctionTrackerItem.setTitle(title);
    auctionTrackerItem.setTimestamp(timestamp);
    auctionTrackerItem.setUrl(itemUrl);
    auctionTrackerItem.setTtl(timestamp.plus(30, ChronoUnit.DAYS).getEpochSecond());
    return auctionTrackerItem;
  }
}
