package com.jordansimsmith.footballcalendar;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class FootballCalendarItem {
  public static final String DELIMITER = "#";
  public static final String TEAM_PREFIX = "TEAM" + DELIMITER;
  public static final String MATCH_PREFIX = "MATCH" + DELIMITER;

  private static final String PK = "pk";
  private static final String SK = "sk";
  private static final String HOME_TEAM = "home_team";
  private static final String AWAY_TEAM = "away_team";
  private static final String TIMESTAMP = "timestamp";
  private static final String VENUE = "venue";
  private static final String ADDRESS = "address";
  private static final String LATITUDE = "latitude";
  private static final String LONGITUDE = "longitude";
  private static final String STATUS = "status";
  private static final String MATCH_ID = "match_id";
  private static final String TEAM = "team";

  private String pk;
  private String sk;
  private String homeTeam;
  private String awayTeam;
  private Instant timestamp;
  private String venue;
  private String address;
  private Double latitude;
  private Double longitude;
  private String status;
  private String matchId;
  private String team;

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

  @DynamoDbAttribute(HOME_TEAM)
  public String getHomeTeam() {
    return homeTeam;
  }

  public void setHomeTeam(String homeTeam) {
    this.homeTeam = homeTeam;
  }

  @DynamoDbAttribute(AWAY_TEAM)
  public String getAwayTeam() {
    return awayTeam;
  }

  public void setAwayTeam(String awayTeam) {
    this.awayTeam = awayTeam;
  }

  @DynamoDbAttribute(TIMESTAMP)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  @DynamoDbAttribute(VENUE)
  public String getVenue() {
    return venue;
  }

  public void setVenue(String venue) {
    this.venue = venue;
  }

  @DynamoDbAttribute(ADDRESS)
  public String getAddress() {
    return address;
  }

  public void setAddress(String address) {
    this.address = address;
  }

  @DynamoDbAttribute(LATITUDE)
  public Double getLatitude() {
    return latitude;
  }

  public void setLatitude(Double latitude) {
    this.latitude = latitude;
  }

  @DynamoDbAttribute(LONGITUDE)
  public Double getLongitude() {
    return longitude;
  }

  public void setLongitude(Double longitude) {
    this.longitude = longitude;
  }

  @DynamoDbAttribute(STATUS)
  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  @DynamoDbAttribute(MATCH_ID)
  public String getMatchId() {
    return matchId;
  }

  public void setMatchId(String matchId) {
    this.matchId = matchId;
  }

  @DynamoDbAttribute(TEAM)
  public String getTeam() {
    return team;
  }

  public void setTeam(String team) {
    this.team = team;
  }

  public static String formatPk(String team) {
    return TEAM_PREFIX + team;
  }

  public static String formatSk(String matchId) {
    return MATCH_PREFIX + matchId;
  }

  public static FootballCalendarItem create(
      String team,
      String matchId,
      String homeTeam,
      String awayTeam,
      Instant timestamp,
      String venue,
      String address,
      Double latitude,
      Double longitude,
      String status) {
    FootballCalendarItem item = new FootballCalendarItem();
    item.setPk(formatPk(team));
    item.setSk(formatSk(matchId));
    item.setTeam(team);
    item.setMatchId(matchId);
    item.setHomeTeam(homeTeam);
    item.setAwayTeam(awayTeam);
    item.setTimestamp(timestamp);
    item.setVenue(venue);
    item.setAddress(address);
    item.setLatitude(latitude);
    item.setLongitude(longitude);
    item.setStatus(status);
    return item;
  }
}
