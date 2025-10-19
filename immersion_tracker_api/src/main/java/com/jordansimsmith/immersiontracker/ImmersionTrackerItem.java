package com.jordansimsmith.immersiontracker;

import com.jordansimsmith.dynamodb.DurationSecondsConverter;
import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

@DynamoDbBean
public class ImmersionTrackerItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String EPISODE_PREFIX = "EPISODE" + DELIMITER;
  public static final String SHOW_PREFIX = "SHOW" + DELIMITER;
  public static final String YOUTUBEVIDEO_PREFIX = "YOUTUBEVIDEO" + DELIMITER;
  public static final String YOUTUBECHANNEL_PREFIX = "YOUTUBECHANNEL" + DELIMITER;

  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String USER = "user";
  public static final String FOLDER_NAME = "folder_name";
  public static final String FILE_NAME = "file_name";
  public static final String TIMESTAMP = "timestamp";
  public static final String TVDB_ID = "tvdb_id";
  public static final String TVDB_NAME = "tvdb_name";
  public static final String TVDB_IMAGE = "tvdb_image";
  public static final String YOUTUBE_VIDEO_ID = "youtube_video_id";
  public static final String YOUTUBE_VIDEO_TITLE = "youtube_video_title";
  public static final String YOUTUBE_CHANNEL_ID = "youtube_channel_id";
  public static final String YOUTUBE_CHANNEL_TITLE = "youtube_channel_title";
  public static final String YOUTUBE_VIDEO_DURATION = "youtube_video_duration";
  public static final String VERSION = "version";

  private String pk;
  private String sk;
  private String user;
  private String folderName;
  private String fileName;
  private Instant timestamp;
  private Integer tvdbId;
  private String tvdbName;
  private String tvdbImage;
  private String youtubeVideoId;
  private String youtubeVideoTitle;
  private String youtubeChannelId;
  private String youtubeChannelTitle;
  private Duration youtubeVideoDuration;
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
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
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

  @DynamoDbAttribute(YOUTUBE_VIDEO_ID)
  public String getYoutubeVideoId() {
    return youtubeVideoId;
  }

  public void setYoutubeVideoId(String youtubeVideoId) {
    this.youtubeVideoId = youtubeVideoId;
  }

  @DynamoDbAttribute(YOUTUBE_VIDEO_TITLE)
  public String getYoutubeVideoTitle() {
    return youtubeVideoTitle;
  }

  public void setYoutubeVideoTitle(String youtubeVideoTitle) {
    this.youtubeVideoTitle = youtubeVideoTitle;
  }

  @DynamoDbAttribute(YOUTUBE_CHANNEL_ID)
  public String getYoutubeChannelId() {
    return youtubeChannelId;
  }

  public void setYoutubeChannelId(String youtubeChannelId) {
    this.youtubeChannelId = youtubeChannelId;
  }

  @DynamoDbAttribute(YOUTUBE_CHANNEL_TITLE)
  public String getYoutubeChannelTitle() {
    return youtubeChannelTitle;
  }

  public void setYoutubeChannelTitle(String youtubeChannelTitle) {
    this.youtubeChannelTitle = youtubeChannelTitle;
  }

  @DynamoDbAttribute(YOUTUBE_VIDEO_DURATION)
  @DynamoDbConvertedBy(DurationSecondsConverter.class)
  public Duration getYoutubeVideoDuration() {
    return youtubeVideoDuration;
  }

  public void setYoutubeVideoDuration(Duration youtubeVideoDuration) {
    this.youtubeVideoDuration = youtubeVideoDuration;
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
        + ", youtubeVideoId='"
        + youtubeVideoId
        + '\''
        + ", youtubeVideoTitle='"
        + youtubeVideoTitle
        + '\''
        + ", youtubeChannelId='"
        + youtubeChannelId
        + '\''
        + ", youtubeChannelTitle='"
        + youtubeChannelTitle
        + '\''
        + ", youtubeVideoDuration="
        + youtubeVideoDuration
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
        && Objects.equals(tvdbImage, that.tvdbImage)
        && Objects.equals(youtubeVideoId, that.youtubeVideoId)
        && Objects.equals(youtubeVideoTitle, that.youtubeVideoTitle)
        && Objects.equals(youtubeChannelId, that.youtubeChannelId)
        && Objects.equals(youtubeChannelTitle, that.youtubeChannelTitle)
        && Objects.equals(youtubeVideoDuration, that.youtubeVideoDuration);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        pk,
        sk,
        user,
        folderName,
        fileName,
        timestamp,
        tvdbId,
        tvdbName,
        tvdbImage,
        youtubeVideoId,
        youtubeVideoTitle,
        youtubeChannelId,
        youtubeChannelTitle,
        youtubeVideoDuration);
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

  public static String formatYoutubeVideoSk(String videoId) {
    return YOUTUBEVIDEO_PREFIX + videoId;
  }

  public static String formatYoutubeChannelSk(String channelId) {
    return YOUTUBECHANNEL_PREFIX + channelId;
  }

  public static ImmersionTrackerItem createEpisode(
      String user, String folderName, String fileName, Instant timestamp) {
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

  public static ImmersionTrackerItem createYoutubeVideo(
      String user,
      String channelId,
      String videoId,
      String title,
      Duration duration,
      Instant timestamp) {
    var video = new ImmersionTrackerItem();
    video.setPk(formatPk(user));
    video.setSk(formatYoutubeVideoSk(videoId));
    video.setUser(user);
    video.setYoutubeVideoId(videoId);
    video.setYoutubeVideoTitle(title);
    video.setYoutubeChannelId(channelId);
    video.setYoutubeVideoDuration(duration);
    video.setTimestamp(timestamp);
    return video;
  }

  public static ImmersionTrackerItem createYoutubeChannel(
      String user, String channelId, String channelTitle) {
    var channel = new ImmersionTrackerItem();
    channel.setPk(formatPk(user));
    channel.setSk(formatYoutubeChannelSk(channelId));
    channel.setUser(user);
    channel.setYoutubeChannelId(channelId);
    channel.setYoutubeChannelTitle(channelTitle);
    return channel;
  }
}
