package com.jordansimsmith.immersiontracker;

import java.util.Objects;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class ImmersionTrackerItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String EPISODE_PREFIX = "EPISODE" + DELIMITER;
  public static final String SHOW_PREFIX = "SHOW" + DELIMITER;

  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String USER = "user";
  public static final String FOLDER_NAME = "folder_name";
  public static final String FILE_NAME = "file_name";
  public static final String TIMESTAMP = "timestamp";
  public static final String TVDB_ID = "tvdb_id";
  public static final String TVDB_NAME = "tvdb_name";
  public static final String TVDB_IMAGE = "tvdb_image";
  public static final String VERSION = "version";

  private String pk;
  private String sk;
  private String user;
  private String folderName;
  private String fileName;
  private Long timestamp;
  private Integer tvdbId;
  private String tvdbName;
  private String tvdbImage;
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

  @DynamoDbAttribute(USER)
  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
  }

  @DynamoDbAttribute(FOLDER_NAME)
  public String getFolderName() {
    return folderName;
  }

  public void setFolderName(String folderName) {
    this.folderName = folderName;
  }

  @DynamoDbAttribute(FILE_NAME)
  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  @DynamoDbAttribute(TIMESTAMP)
  public Long getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Long timestamp) {
    this.timestamp = timestamp;
  }

  @DynamoDbAttribute(TVDB_ID)
  public Integer getTvdbId() {
    return tvdbId;
  }

  public void setTvdbId(Integer tvdbId) {
    this.tvdbId = tvdbId;
  }

  @DynamoDbAttribute(TVDB_NAME)
  public String getTvdbName() {
    return tvdbName;
  }

  public void setTvdbName(String tvdbName) {
    this.tvdbName = tvdbName;
  }

  @DynamoDbAttribute(TVDB_IMAGE)
  public String getTvdbImage() {
    return tvdbImage;
  }

  public void setTvdbImage(String tvdbImage) {
    this.tvdbImage = tvdbImage;
  }

  @DynamoDbVersionAttribute()
  @DynamoDbAttribute(VERSION)
  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }

  @Override
  public String toString() {
    return "ImmersionTrackerItem{"
        + "pk='"
        + pk
        + '\''
        + ", sk='"
        + sk
        + '\''
        + ", user='"
        + user
        + '\''
        + ", folderName='"
        + folderName
        + '\''
        + ", fileName='"
        + fileName
        + '\''
        + ", timestamp="
        + timestamp
        + ", tvdbId="
        + tvdbId
        + ", tvdbName="
        + tvdbName
        + ", tvdbImage='"
        + tvdbImage
        + '\''
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ImmersionTrackerItem that = (ImmersionTrackerItem) o;
    return Objects.equals(pk, that.pk)
        && Objects.equals(sk, that.sk)
        && Objects.equals(user, that.user)
        && Objects.equals(folderName, that.folderName)
        && Objects.equals(fileName, that.fileName)
        && Objects.equals(timestamp, that.timestamp)
        && Objects.equals(tvdbId, that.tvdbId)
        && Objects.equals(tvdbName, that.tvdbName)
        && Objects.equals(tvdbImage, that.tvdbImage);
  }

  @Override
  public int hashCode() {
    return Objects.hash(pk, sk, user, folderName, fileName, timestamp, tvdbId, tvdbName, tvdbImage);
  }

  public static String formatPk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatEpisodeSk(String folderName, String fileName) {
    return EPISODE_PREFIX + folderName + DELIMITER + fileName;
  }

  public static String formatShowSk(String folderName) {
    return SHOW_PREFIX + folderName;
  }

  public static ImmersionTrackerItem createEpisode(
      String user, String folderName, String fileName, long timestamp) {
    var episode = new ImmersionTrackerItem();
    episode.setPk(formatPk(user));
    episode.setSk(formatEpisodeSk(folderName, fileName));
    episode.setFolderName(folderName);
    episode.setFileName(fileName);
    episode.setTimestamp(timestamp);
    episode.setUser(user);
    return episode;
  }

  public static ImmersionTrackerItem createShow(String user, String folderName) {
    var show = new ImmersionTrackerItem();
    show.setPk(formatPk(user));
    show.setSk(formatShowSk(folderName));
    show.setFolderName(folderName);
    show.setUser(user);
    return show;
  }
}
