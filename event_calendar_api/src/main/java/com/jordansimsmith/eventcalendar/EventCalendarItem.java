package com.jordansimsmith.eventcalendar;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import java.util.Objects;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

@DynamoDbBean
public class EventCalendarItem {
  public static final String DELIMITER = "#";
  public static final String STADIUM_PREFIX = "STADIUM" + DELIMITER;
  public static final String EVENT_PREFIX = "EVENT" + DELIMITER;

  private static final String PK = "pk";
  private static final String SK = "sk";
  private static final String TITLE = "title";
  private static final String EVENT_URL = "event_url";
  private static final String EVENT_INFO = "event_info";
  private static final String TIMESTAMP = "timestamp";
  private static final String STADIUM_URL = "stadium_url";

  private String pk;
  private String sk;
  private String title;
  private String eventUrl;
  private String eventInfo;
  private Instant timestamp;
  private String stadiumUrl;

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

  @DynamoDbAttribute(EVENT_URL)
  public String getEventUrl() {
    return eventUrl;
  }

  public void setEventUrl(String eventUrl) {
    this.eventUrl = eventUrl;
  }

  @DynamoDbAttribute(EVENT_INFO)
  public String getEventInfo() {
    return eventInfo;
  }

  public void setEventInfo(String eventInfo) {
    this.eventInfo = eventInfo;
  }

  @DynamoDbAttribute(TIMESTAMP)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  @DynamoDbAttribute(STADIUM_URL)
  public String getStadiumUrl() {
    return stadiumUrl;
  }

  public void setStadiumUrl(String stadiumUrl) {
    this.stadiumUrl = stadiumUrl;
  }

  @Override
  public String toString() {
    return "EventCalendarItem{"
        + "pk='"
        + pk
        + '\''
        + ", sk='"
        + sk
        + '\''
        + ", title='"
        + title
        + '\''
        + ", eventUrl='"
        + eventUrl
        + '\''
        + ", eventInfo='"
        + eventInfo
        + '\''
        + ", timestamp="
        + timestamp
        + ", stadiumUrl='"
        + stadiumUrl
        + '\''
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    EventCalendarItem that = (EventCalendarItem) o;
    return Objects.equals(pk, that.pk)
        && Objects.equals(sk, that.sk)
        && Objects.equals(title, that.title)
        && Objects.equals(eventUrl, that.eventUrl)
        && Objects.equals(eventInfo, that.eventInfo)
        && Objects.equals(timestamp, that.timestamp)
        && Objects.equals(stadiumUrl, that.stadiumUrl);
  }

  @Override
  public int hashCode() {
    return Objects.hash(pk, sk, title, eventUrl, eventInfo, timestamp, stadiumUrl);
  }

  public static String formatPk(String stadiumUrl) {
    return STADIUM_PREFIX + stadiumUrl;
  }

  public static String formatSk(String eventUrl) {
    return EVENT_PREFIX + eventUrl;
  }

  public static EventCalendarItem create(
      String stadiumUrl, String title, String eventUrl, String eventInfo, Instant timestamp) {
    var eventCalendarItem = new EventCalendarItem();
    eventCalendarItem.setPk(formatPk(stadiumUrl));
    eventCalendarItem.setSk(formatSk(eventUrl));
    eventCalendarItem.setTitle(title);
    eventCalendarItem.setEventUrl(eventUrl);
    eventCalendarItem.setEventInfo(eventInfo);
    eventCalendarItem.setTimestamp(timestamp);
    eventCalendarItem.setStadiumUrl(stadiumUrl);
    return eventCalendarItem;
  }
}
