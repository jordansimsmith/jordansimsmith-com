package com.jordansimsmith.immersiontracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.jordansimsmith.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class GetProgressHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(GetProgressHandler.class);
  @VisibleForTesting static final ZoneId ZONE_ID = ZoneId.of("Pacific/Auckland");
  private static final int MINUTES_PER_EPISODE = 20;

  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;

  @VisibleForTesting
  record GetProgressResponse(
      @JsonProperty("total_episodes_watched") int totalEpisodesWatched,
      @JsonProperty("total_hours_watched") int totalHoursWatched,
      @JsonProperty("episodes_watched_today") int episodesWatchedToday,
      @JsonProperty("youtube_videos_watched") int youtubeVideosWatched,
      @JsonProperty("youtube_videos_watched_today") int youtubeVideosWatchedToday,
      @JsonProperty("days_since_first_episode") long daysSinceFirstEpisode,
      @Nullable @JsonProperty("weekly_trend_percentage") Double weeklyTrendPercentage,
      @JsonProperty("shows") List<Show> shows,
      @JsonProperty("youtube_channels") List<YoutubeChannel> youtubeChannels) {}

  @VisibleForTesting
  record Show(
      @Nullable @JsonProperty("name") String name,
      @JsonProperty("episodes_watched") int episodesWatched) {}

  @VisibleForTesting
  record YoutubeChannel(
      @Nullable @JsonProperty("channel_name") String channelName,
      @JsonProperty("videos_watched") int videosWatched) {}

  private record EpisodeShow(ImmersionTrackerItem episode, @Nullable ImmersionTrackerItem show) {}

  public GetProgressHandler() {
    this(ImmersionTrackerFactory.create());
  }

  @VisibleForTesting
  GetProgressHandler(ImmersionTrackerFactory factory) {
    this.clock = factory.clock();
    this.objectMapper = factory.objectMapper();
    this.immersionTrackerTable = factory.immersionTrackerTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("Error processing progress request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var user = event.getQueryStringParameters().get("user");
    Preconditions.checkNotNull(user);

    var query =
        immersionTrackerTable.query(
            QueryEnhancedRequest.builder()
                .queryConditional(
                    QueryConditional.keyEqualTo(
                        b -> b.partitionValue(ImmersionTrackerItem.formatPk(user))))
                .build());
    var items = query.items().stream().toList();

    var episodes =
        items.stream()
            .filter(i -> i.getSk().startsWith(ImmersionTrackerItem.EPISODE_PREFIX))
            .toList();
    var shows =
        items.stream().filter(i -> i.getSk().startsWith(ImmersionTrackerItem.SHOW_PREFIX)).toList();
    var youtubeVideos =
        items.stream()
            .filter(i -> i.getSk().startsWith(ImmersionTrackerItem.YOUTUBEVIDEO_PREFIX))
            .toList();
    var youtubeChannels =
        items.stream()
            .filter(i -> i.getSk().startsWith(ImmersionTrackerItem.YOUTUBECHANNEL_PREFIX))
            .toList();

    var now = clock.now();
    var today = now.atZone(ZONE_ID).truncatedTo(ChronoUnit.DAYS).toInstant();

    var totalEpisodesWatched = totalEpisodesWatched(episodes);
    var totalHoursWatched = totalHoursWatched(episodes, youtubeVideos);
    var episodesWatchedToday = episodesWatchedToday(episodes, today);
    var youtubeVideosWatched = youtubeVideosWatched(youtubeVideos);
    var youtubeVideosWatchedToday = youtubeVideosWatchedToday(youtubeVideos, today);
    var daysSinceFirstEpisode = daysSinceFirstEpisode(episodes, youtubeVideos, now);
    var weeklyTrendPercentage =
        weeklyTrendPercentage(episodes, youtubeVideos, now, daysSinceFirstEpisode);
    var progresses = shows(episodes, shows);
    var channelProgresses = youtubeChannels(youtubeVideos, youtubeChannels);

    var res =
        new GetProgressResponse(
            totalEpisodesWatched,
            totalHoursWatched,
            episodesWatchedToday,
            youtubeVideosWatched,
            youtubeVideosWatchedToday,
            daysSinceFirstEpisode,
            weeklyTrendPercentage,
            progresses,
            channelProgresses);

    return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(200)
        .withHeaders(Map.of("Content-Type", "application/json; charset=utf-8"))
        .withBody(objectMapper.writeValueAsString(res))
        .build();
  }

  private int totalEpisodesWatched(List<ImmersionTrackerItem> episodes) {
    return episodes.size();
  }

  private int totalHoursWatched(
      List<ImmersionTrackerItem> episodes, List<ImmersionTrackerItem> youtubeVideos) {
    var youtubeTotalMinutes =
        youtubeVideos.stream().mapToLong(v -> v.getYoutubeVideoDuration().toMinutes()).sum();
    return (int) ((episodes.size() * MINUTES_PER_EPISODE + youtubeTotalMinutes) / 60);
  }

  private int episodesWatchedToday(List<ImmersionTrackerItem> episodes, Instant today) {
    return episodes.stream().filter(e -> e.getTimestamp().isAfter(today)).toList().size();
  }

  private int youtubeVideosWatched(List<ImmersionTrackerItem> youtubeVideos) {
    return youtubeVideos.size();
  }

  private int youtubeVideosWatchedToday(List<ImmersionTrackerItem> youtubeVideos, Instant today) {
    return youtubeVideos.stream().filter(v -> v.getTimestamp().isAfter(today)).toList().size();
  }

  private long daysSinceFirstEpisode(
      List<ImmersionTrackerItem> episodes, List<ImmersionTrackerItem> youtubeVideos, Instant now) {
    var firstEpisodeWatched =
        episodes.stream().map(ImmersionTrackerItem::getTimestamp).min(Instant::compareTo);
    var firstYoutubeWatched =
        youtubeVideos.stream().map(ImmersionTrackerItem::getTimestamp).min(Instant::compareTo);
    var firstContentWatched =
        Stream.of(firstEpisodeWatched, firstYoutubeWatched)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .min(Instant::compareTo)
            .orElse(now);
    return ChronoUnit.DAYS.between(firstContentWatched, now);
  }

  private Double weeklyTrendPercentage(
      List<ImmersionTrackerItem> episodes,
      List<ImmersionTrackerItem> youtubeVideos,
      Instant now,
      long daysSinceFirstEpisode) {
    if (daysSinceFirstEpisode < 14) {
      return null;
    }
    var sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);
    var episodesWatchedLastWeek =
        episodes.stream()
            .filter(e -> e.getTimestamp().isAfter(sevenDaysAgo))
            .mapToLong(e -> MINUTES_PER_EPISODE)
            .sum();
    var youtubeWatchedLastWeek =
        youtubeVideos.stream()
            .filter(v -> v.getTimestamp().isAfter(sevenDaysAgo))
            .mapToLong(v -> v.getYoutubeVideoDuration().toMinutes())
            .sum();
    var totalMinutesWatchedLastWeek = episodesWatchedLastWeek + youtubeWatchedLastWeek;
    var youtubeTotalMinutes =
        youtubeVideos.stream().mapToLong(v -> v.getYoutubeVideoDuration().toMinutes()).sum();
    var totalMinutesWatched = (long) episodes.size() * MINUTES_PER_EPISODE + youtubeTotalMinutes;
    var averageMinutesPerWeek = (double) totalMinutesWatched / daysSinceFirstEpisode * 7;
    return ((totalMinutesWatchedLastWeek - averageMinutesPerWeek) / averageMinutesPerWeek) * 100;
  }

  private List<Show> shows(List<ImmersionTrackerItem> episodes, List<ImmersionTrackerItem> shows) {
    var showsByFolderName =
        shows.stream().collect(Collectors.toMap(ImmersionTrackerItem::getFolderName, v -> v));
    var showEpisodes =
        episodes.stream()
            .map(e -> new EpisodeShow(e, showsByFolderName.get(e.getFolderName())))
            .toList();
    var unknownShows = showEpisodes.stream().filter(e -> e.show() == null).toList();
    var unknownShowsProgress =
        !unknownShows.isEmpty()
            ? Stream.of(new Show(null, unknownShows.size()))
            : Stream.<Show>empty();
    var knownShows =
        showEpisodes.stream()
            .filter(e -> e.show() != null)
            .collect(Collectors.groupingBy(e -> Objects.requireNonNull(e.show().getTvdbId())));
    var knownShowsProgress =
        knownShows.values().stream()
            .map(e -> new Show(Objects.requireNonNull(e.get(0).show()).getTvdbName(), e.size()));
    return Stream.concat(unknownShowsProgress, knownShowsProgress)
        .sorted(Comparator.comparing(e -> e.episodesWatched, Comparator.reverseOrder()))
        .toList();
  }

  private List<YoutubeChannel> youtubeChannels(
      List<ImmersionTrackerItem> youtubeVideos, List<ImmersionTrackerItem> youtubeChannels) {
    var channelsByChannelId =
        youtubeChannels.stream()
            .collect(Collectors.toMap(ImmersionTrackerItem::getYoutubeChannelId, v -> v));
    var videosByChannelId =
        youtubeVideos.stream()
            .collect(Collectors.groupingBy(ImmersionTrackerItem::getYoutubeChannelId));
    return videosByChannelId.entrySet().stream()
        .map(
            entry -> {
              var channelId = entry.getKey();
              var videos = entry.getValue();
              var channel = channelsByChannelId.get(channelId);
              var channelName = channel != null ? channel.getYoutubeChannelTitle() : null;
              return new YoutubeChannel(channelName, videos.size());
            })
        .sorted(
            Comparator.comparing((YoutubeChannel c) -> c.videosWatched, Comparator.reverseOrder())
                .thenComparing(c -> c.channelName, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }
}
