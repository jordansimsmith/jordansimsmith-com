package com.jordansimsmith.pricetracker;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import java.util.Objects;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

@DynamoDbBean
public class PriceTrackerItem {
  public static final String DELIMITER = "#";
  public static final String PRODUCT_PREFIX = "PRODUCT" + DELIMITER;
  public static final String TIMESTAMP_PREFIX = "TIMESTAMP" + DELIMITER;

  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String PRICE = "price";
  public static final String TIMESTAMP = "timestamp";
  public static final String NAME = "product";
  public static final String URL = "url";
  public static final String VERSION = "version";

  private String pk;
  private String sk;
  private Double price;
  private Instant timestamp;
  private String name;
  private String url;
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

  @DynamoDbAttribute(PRICE)
  public Double getPrice() {
    return price;
  }

  public void setPrice(Double price) {
    this.price = price;
  }

  @DynamoDbAttribute(TIMESTAMP)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  @DynamoDbAttribute(NAME)
  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  @DynamoDbAttribute(URL)
  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
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
    return "PriceTrackerItem{"
        + "pk='"
        + pk
        + '\''
        + ", sk='"
        + sk
        + '\''
        + ", price='"
        + price
        + '\''
        + ", timestamp='"
        + timestamp
        + '\''
        + ", name='"
        + name
        + '\''
        + ", url='"
        + url
        + '\''
        + ", version='"
        + version
        + '\''
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    PriceTrackerItem priceTrackerItem = (PriceTrackerItem) o;
    return Objects.equals(pk, priceTrackerItem.pk)
        && Objects.equals(sk, priceTrackerItem.sk)
        && Objects.equals(price, priceTrackerItem.price)
        && Objects.equals(timestamp, priceTrackerItem.timestamp)
        && Objects.equals(name, priceTrackerItem.name)
        && Objects.equals(url, priceTrackerItem.url)
        && Objects.equals(version, priceTrackerItem.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(pk, sk, price, timestamp, name, url, version);
  }

  public static String formatPk(String url) {
    return PRODUCT_PREFIX + url;
  }

  public static String formatSk(Instant timestamp) {
    return TIMESTAMP_PREFIX + String.format("%010d", timestamp.getEpochSecond());
  }

  public static PriceTrackerItem create(String url, String name, Instant timestamp, double price) {
    var priceTrackerItem = new PriceTrackerItem();
    priceTrackerItem.setPk(formatPk(url));
    priceTrackerItem.setSk(formatSk(timestamp));
    priceTrackerItem.setPrice(price);
    priceTrackerItem.setTimestamp(timestamp);
    priceTrackerItem.setName(name);
    priceTrackerItem.setUrl(url);
    return priceTrackerItem;
  }
}
