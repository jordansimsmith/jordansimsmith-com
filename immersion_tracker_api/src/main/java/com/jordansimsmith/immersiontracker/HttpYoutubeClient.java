package com.jordansimsmith.immersiontracker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.jordansimsmith.secrets.Secrets;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

public class HttpYoutubeClient implements YoutubeClient {
  @VisibleForTesting static final String SECRET = "immersion_tracker_api";
  private static final Pattern ISO_8601_DURATION_PATTERN =
      Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?");

  private final ObjectMapper objectMapper;
  private final Secrets secrets;
  private final HttpClient httpClient;

  public HttpYoutubeClient(ObjectMapper objectMapper, Secrets secrets, HttpClient httpClient) {
    this.objectMapper = objectMapper;
    this.secrets = secrets;
    this.httpClient = httpClient;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record VideoListResponse(@JsonProperty("items") List<VideoItem> items) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record VideoItem(
      @JsonProperty("id") String id,
      @JsonProperty("snippet") VideoSnippet snippet,
      @JsonProperty("contentDetails") VideoContentDetails contentDetails) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record VideoSnippet(
      @JsonProperty("title") String title,
      @JsonProperty("channelId") String channelId,
      @JsonProperty("channelTitle") String channelTitle) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record VideoContentDetails(@JsonProperty("duration") String duration) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ChannelListResponse(@JsonProperty("items") List<ChannelItem> items) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ChannelItem(
      @JsonProperty("id") String id, @JsonProperty("snippet") ChannelSnippet snippet) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ChannelSnippet(
      @JsonProperty("title") String title,
      @JsonProperty("thumbnails") ChannelThumbnails thumbnails) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ChannelThumbnails(
      @JsonProperty("default") Thumbnail defaultThumbnail,
      @JsonProperty("medium") Thumbnail medium,
      @JsonProperty("high") Thumbnail high) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Thumbnail(@JsonProperty("url") String url) {}

  @Override
  public Video getVideo(String videoId) {
    try {
      return doGetVideo(videoId);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Video doGetVideo(String videoId) throws Exception {
    var secret = secrets.get(SECRET);
    var apiKey = objectMapper.readTree(secret).get("youtube_api_key").asText(null);
    Preconditions.checkNotNull(apiKey, "youtube_api_key not found in secret");

    var request =
        HttpRequest.newBuilder()
            .uri(
                new URI(
                    "https://www.googleapis.com/youtube/v3/videos"
                        + "?part=id,snippet,contentDetails"
                        + "&id="
                        + videoId
                        + "&key="
                        + apiKey))
            .header("Accept", "application/json")
            .GET()
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new IOException(
          "YouTube API request failed with status code "
              + response.statusCode()
              + " and body: "
              + response.body());
    }

    var responseBody = objectMapper.readValue(response.body(), VideoListResponse.class);

    Preconditions.checkState(
        responseBody.items().size() == 1,
        "Expected exactly 1 video, got %s",
        responseBody.items().size());

    var item = responseBody.items().get(0);
    Preconditions.checkState(
        videoId.equals(item.id()), "Expected video ID %s, got %s", videoId, item.id());

    Preconditions.checkNotNull(item.snippet().title(), "Video title is null");
    Preconditions.checkNotNull(item.snippet().channelId(), "Channel ID is null");
    Preconditions.checkNotNull(item.snippet().channelTitle(), "Channel title is null");
    Preconditions.checkNotNull(item.contentDetails().duration(), "Video duration is null");

    var duration = parseIso8601Duration(item.contentDetails().duration());

    return new Video(
        item.id(),
        item.snippet().title(),
        item.snippet().channelId(),
        item.snippet().channelTitle(),
        duration);
  }

  @Override
  public Channel getChannel(String channelId) {
    try {
      return doGetChannel(channelId);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Channel doGetChannel(String channelId) throws Exception {
    var secret = secrets.get(SECRET);
    var apiKey = objectMapper.readTree(secret).get("youtube_api_key").asText(null);
    Preconditions.checkNotNull(apiKey, "youtube_api_key not found in secret");

    var request =
        HttpRequest.newBuilder()
            .uri(
                new URI(
                    "https://www.googleapis.com/youtube/v3/channels"
                        + "?part=snippet"
                        + "&id="
                        + channelId
                        + "&key="
                        + apiKey))
            .header("Accept", "application/json")
            .GET()
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new IOException(
          "YouTube API request failed with status code "
              + response.statusCode()
              + " and body: "
              + response.body());
    }

    var responseBody = objectMapper.readValue(response.body(), ChannelListResponse.class);

    Preconditions.checkState(
        responseBody.items().size() == 1,
        "Expected exactly 1 channel, got %s",
        responseBody.items().size());

    var item = responseBody.items().get(0);
    Preconditions.checkState(
        channelId.equals(item.id()), "Expected channel ID %s, got %s", channelId, item.id());

    Preconditions.checkNotNull(item.snippet().title(), "Channel title is null");

    var artworkUrl = selectBestThumbnail(item.snippet().thumbnails());

    return new Channel(item.id(), item.snippet().title(), artworkUrl);
  }

  private String selectBestThumbnail(ChannelThumbnails thumbnails) {
    if (thumbnails == null) {
      return null;
    }
    if (thumbnails.high() != null && thumbnails.high().url() != null) {
      return thumbnails.high().url();
    }
    if (thumbnails.medium() != null && thumbnails.medium().url() != null) {
      return thumbnails.medium().url();
    }
    if (thumbnails.defaultThumbnail() != null && thumbnails.defaultThumbnail().url() != null) {
      return thumbnails.defaultThumbnail().url();
    }
    return null;
  }

  @VisibleForTesting
  static Duration parseIso8601Duration(String iso8601Duration) {
    var matcher = ISO_8601_DURATION_PATTERN.matcher(iso8601Duration);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid ISO 8601 duration format: " + iso8601Duration);
    }

    var hours = matcher.group(1) != null ? Long.parseLong(matcher.group(1)) : 0;
    var minutes = matcher.group(2) != null ? Long.parseLong(matcher.group(2)) : 0;
    var seconds = matcher.group(3) != null ? Long.parseLong(matcher.group(3)) : 0;

    return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds);
  }
}
