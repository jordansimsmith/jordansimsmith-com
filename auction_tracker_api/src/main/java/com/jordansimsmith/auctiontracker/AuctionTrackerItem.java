package com.jordansimsmith.auctiontracker;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import javax.annotation.Nullable;
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
  public static final String GSI1PK = "gsi1pk";
  public static final String GSI1SK = "gsi1sk";

  private String pk;
  private String sk;
  private String title;
  private Instant timestamp;
  private String url;
  private Long ttl;
  private Long version;
  private String gsi1pk;
  private String gsi1sk;

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

  @DynamoDbSecondaryPartitionKey(indexNames = "gsi1")
  @DynamoDbAttribute(GSI1PK)
  public String getGsi1pk() {
    return gsi1pk;
  }

  public void setGsi1pk(String gsi1pk) {
    this.gsi1pk = gsi1pk;
  }

  @DynamoDbSecondarySortKey(indexNames = "gsi1")
  @DynamoDbAttribute(GSI1SK)
  public String getGsi1sk() {
    return gsi1sk;
  }

  public void setGsi1sk(String gsi1sk) {
    this.gsi1sk = gsi1sk;
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
        + ", gsi1pk='"
        + gsi1pk
        + '\''
        + ", gsi1sk='"
        + gsi1sk
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
        && Objects.equals(version, auctionTrackerItem.version)
        && Objects.equals(gsi1pk, auctionTrackerItem.gsi1pk)
        && Objects.equals(gsi1sk, auctionTrackerItem.gsi1sk);
  }

  @Override
  public int hashCode() {
    return Objects.hash(pk, sk, title, timestamp, url, ttl, version, gsi1pk, gsi1sk);
  }

  public static String formatPk(String searchUrl) {
    return SEARCH_PREFIX + searchUrl;
  }

  public static String formatSk(Instant timestamp, @Nullable String itemUrl) {
    var formattedSk = TIMESTAMP_PREFIX + String.format("%010d", timestamp.getEpochSecond());
    if (itemUrl != null) {
      formattedSk += ITEM_PREFIX + itemUrl;
    }
    return formattedSk;
  }

  public static String formatGsi1pk(String searchUrl) {
    return SEARCH_PREFIX + searchUrl;
  }

  public static String formatGsi1sk(String itemUrl) {
    return ITEM_PREFIX + itemUrl;
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
    auctionTrackerItem.setGsi1pk(formatGsi1pk(searchUrl));
    auctionTrackerItem.setGsi1sk(formatGsi1sk(itemUrl));
    return auctionTrackerItem;
  }
}
