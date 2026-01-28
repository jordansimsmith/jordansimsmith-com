package com.jordansimsmith.immersiontracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
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
  private static final Duration DEFAULT_EPISODE_DURATION = Duration.ofMinutes(20);

  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final RequestContextFactory requestContextFactory;
  private final DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;

  @VisibleForTesting
  record GetProgressResponse(
      @JsonProperty("total_episodes_watched") int totalEpisodesWatched,
      @JsonProperty("total_movies_watched") int totalMoviesWatched,
      @JsonProperty("total_hours_watched") int totalHoursWatched,
      @JsonProperty("episodes_watched_today") int episodesWatchedToday,
      @JsonProperty("movies_watched_today") int moviesWatchedToday,
      @JsonProperty("youtube_videos_watched") int youtubeVideosWatched,
      @JsonProperty("youtube_videos_watched_today") int youtubeVideosWatchedToday,
      @JsonProperty("spotify_episodes_watched") int spotifyEpisodesWatched,
      @JsonProperty("spotify_episodes_watched_today") int spotifyEpisodesWatchedToday,
      @JsonProperty("days_since_first_episode") long daysSinceFirstEpisode,
      @Nullable @JsonProperty("weekly_trend_percentage") Double weeklyTrendPercentage,
      @JsonProperty("daily_activity") List<DailyActivity> dailyActivity,
      @JsonProperty("all_time_progress") List<CumulativeProgress> allTimeProgress,
      @JsonProperty("shows") List<Show> shows,
      @JsonProperty("youtube_channels") List<YoutubeChannel> youtubeChannels,
      @JsonProperty("spotify_shows") List<SpotifyShow> spotifyShows,
      @JsonProperty("movies") List<Movie> movies) {}

  @VisibleForTesting
  record Show(
      @Nullable @JsonProperty("name") String name,
      @JsonProperty("episodes_watched") int episodesWatched) {}

  @VisibleForTesting
  record YoutubeChannel(
      @Nullable @JsonProperty("channel_name") String channelName,
      @JsonProperty("videos_watched") int videosWatched) {}

  @VisibleForTesting
  record SpotifyShow(
      @Nullable @JsonProperty("show_name") String showName,
      @JsonProperty("episodes_watched") int episodesWatched) {}

  @VisibleForTesting
  record Movie(@Nullable @JsonProperty("name") String name) {}

  @VisibleForTesting
  record DailyActivity(
      @JsonProperty("days_ago") int daysAgo, @JsonProperty("minutes_watched") int minutesWatched) {}

  @VisibleForTesting
  record CumulativeProgress(
      @JsonProperty("label") String label, @JsonProperty("cumulative_hours") int cumulativeHours) {}

  private record EpisodeShow(ImmersionTrackerItem episode, @Nullable ImmersionTrackerItem show) {}

  public GetProgressHandler() {
    this(ImmersionTrackerFactory.create());
  }

  @VisibleForTesting
  GetProgressHandler(ImmersionTrackerFactory factory) {
    this.clock = factory.clock();
    this.objectMapper = factory.objectMapper();
    this.requestContextFactory = factory.requestContextFactory();
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
    var user = requestContextFactory.createCtx(event).user();

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
    var showsByFolderName =
        shows.stream()
            .collect(
                Collectors.toMap(
                    ImmersionTrackerItem::getFolderName,
                    v -> v,
                    (existing, replacement) -> existing));
    var youtubeVideos =
        items.stream()
            .filter(i -> i.getSk().startsWith(ImmersionTrackerItem.YOUTUBEVIDEO_PREFIX))
            .toList();
    var youtubeChannels =
        items.stream()
            .filter(i -> i.getSk().startsWith(ImmersionTrackerItem.YOUTUBECHANNEL_PREFIX))
            .toList();
    var spotifyEpisodes =
        items.stream()
            .filter(i -> i.getSk().startsWith(ImmersionTrackerItem.SPOTIFYEPISODE_PREFIX))
            .toList();
    var spotifyShows =
        items.stream()
            .filter(i -> i.getSk().startsWith(ImmersionTrackerItem.SPOTIFYSHOW_PREFIX))
            .toList();
    var movies =
        items.stream()
            .filter(i -> i.getSk().startsWith(ImmersionTrackerItem.MOVIE_PREFIX))
            .toList();

    var now = clock.now();
    var today = now.atZone(ZONE_ID).truncatedTo(ChronoUnit.DAYS).toInstant();

    var totalEpisodesWatched = totalEpisodesWatched(episodes);
    var totalMoviesWatched = totalMoviesWatched(movies);
    var totalHoursWatched =
        totalHoursWatched(episodes, showsByFolderName, youtubeVideos, spotifyEpisodes, movies);
    var episodesWatchedToday = episodesWatchedToday(episodes, today);
    var moviesWatchedToday = moviesWatchedToday(movies, today);
    var youtubeVideosWatched = youtubeVideosWatched(youtubeVideos);
    var youtubeVideosWatchedToday = youtubeVideosWatchedToday(youtubeVideos, today);
    var spotifyEpisodesWatched = spotifyEpisodesWatched(spotifyEpisodes);
    var spotifyEpisodesWatchedToday = spotifyEpisodesWatchedToday(spotifyEpisodes, today);
    var daysSinceFirstEpisode =
        daysSinceFirstEpisode(episodes, youtubeVideos, spotifyEpisodes, movies, now);
    var weeklyTrendPercentage =
        weeklyTrendPercentage(
            episodes,
            showsByFolderName,
            youtubeVideos,
            spotifyEpisodes,
            movies,
            now,
            daysSinceFirstEpisode);
    var activity =
        dailyActivity(episodes, showsByFolderName, youtubeVideos, spotifyEpisodes, movies, now);
    var allTimeProgress =
        allTimeProgress(episodes, showsByFolderName, youtubeVideos, spotifyEpisodes, movies);
    var progresses = shows(episodes, shows);
    var channelProgresses = youtubeChannels(youtubeVideos, youtubeChannels);
    var spotifyShowProgresses = spotifyShows(spotifyEpisodes, spotifyShows);
    var movieProgresses = movies(movies);

    var res =
        new GetProgressResponse(
            totalEpisodesWatched,
            totalMoviesWatched,
            totalHoursWatched,
            episodesWatchedToday,
            moviesWatchedToday,
            youtubeVideosWatched,
            youtubeVideosWatchedToday,
            spotifyEpisodesWatched,
            spotifyEpisodesWatchedToday,
            daysSinceFirstEpisode,
            weeklyTrendPercentage,
            activity,
            allTimeProgress,
            progresses,
            channelProgresses,
            spotifyShowProgresses,
            movieProgresses);

    return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(200)
        .withHeaders(Map.of("Content-Type", "application/json; charset=utf-8"))
        .withBody(objectMapper.writeValueAsString(res))
        .build();
  }

  private int totalEpisodesWatched(List<ImmersionTrackerItem> episodes) {
    return episodes.size();
  }

  private int totalMoviesWatched(List<ImmersionTrackerItem> movies) {
    return movies.size();
  }

  private int totalHoursWatched(
      List<ImmersionTrackerItem> episodes,
      Map<String, ImmersionTrackerItem> showsByFolderName,
      List<ImmersionTrackerItem> youtubeVideos,
      List<ImmersionTrackerItem> spotifyEpisodes,
      List<ImmersionTrackerItem> movies) {
    var episodeDuration =
        episodes.stream()
            .map(e -> getEpisodeDuration(e, showsByFolderName))
            .reduce(Duration.ZERO, Duration::plus);
    var youtubeDuration =
        youtubeVideos.stream()
            .map(ImmersionTrackerItem::getYoutubeVideoDuration)
            .reduce(Duration.ZERO, Duration::plus);
    var spotifyDuration =
        spotifyEpisodes.stream()
            .map(ImmersionTrackerItem::getSpotifyEpisodeDuration)
            .reduce(Duration.ZERO, Duration::plus);
    var movieDuration =
        movies.stream()
            .map(ImmersionTrackerItem::getMovieDuration)
            .reduce(Duration.ZERO, Duration::plus);
    return (int)
        episodeDuration.plus(youtubeDuration).plus(spotifyDuration).plus(movieDuration).toHours();
  }

  private int episodesWatchedToday(List<ImmersionTrackerItem> episodes, Instant today) {
    return episodes.stream().filter(e -> e.getTimestamp().isAfter(today)).toList().size();
  }

  private int moviesWatchedToday(List<ImmersionTrackerItem> movies, Instant today) {
    return movies.stream().filter(m -> m.getTimestamp().isAfter(today)).toList().size();
  }

  private int youtubeVideosWatched(List<ImmersionTrackerItem> youtubeVideos) {
    return youtubeVideos.size();
  }

  private int youtubeVideosWatchedToday(List<ImmersionTrackerItem> youtubeVideos, Instant today) {
    return youtubeVideos.stream().filter(v -> v.getTimestamp().isAfter(today)).toList().size();
  }

  private int spotifyEpisodesWatched(List<ImmersionTrackerItem> spotifyEpisodes) {
    return spotifyEpisodes.size();
  }

  private int spotifyEpisodesWatchedToday(
      List<ImmersionTrackerItem> spotifyEpisodes, Instant today) {
    return spotifyEpisodes.stream().filter(e -> e.getTimestamp().isAfter(today)).toList().size();
  }

  private long daysSinceFirstEpisode(
      List<ImmersionTrackerItem> episodes,
      List<ImmersionTrackerItem> youtubeVideos,
      List<ImmersionTrackerItem> spotifyEpisodes,
      List<ImmersionTrackerItem> movies,
      Instant now) {
    var firstEpisodeWatched =
        episodes.stream().map(ImmersionTrackerItem::getTimestamp).min(Instant::compareTo);
    var firstYoutubeWatched =
        youtubeVideos.stream().map(ImmersionTrackerItem::getTimestamp).min(Instant::compareTo);
    var firstSpotifyWatched =
        spotifyEpisodes.stream().map(ImmersionTrackerItem::getTimestamp).min(Instant::compareTo);
    var firstMovieWatched =
        movies.stream().map(ImmersionTrackerItem::getTimestamp).min(Instant::compareTo);
    var firstContentWatched =
        Stream.of(firstEpisodeWatched, firstYoutubeWatched, firstSpotifyWatched, firstMovieWatched)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .min(Instant::compareTo)
            .orElse(now);
    return ChronoUnit.DAYS.between(firstContentWatched, now);
  }

  private Double weeklyTrendPercentage(
      List<ImmersionTrackerItem> episodes,
      Map<String, ImmersionTrackerItem> showsByFolderName,
      List<ImmersionTrackerItem> youtubeVideos,
      List<ImmersionTrackerItem> spotifyEpisodes,
      List<ImmersionTrackerItem> movies,
      Instant now,
      long daysSinceFirstEpisode) {
    if (daysSinceFirstEpisode < 14) {
      return null;
    }
    var sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);

    var lastWeekEpisodeDuration =
        episodes.stream()
            .filter(e -> e.getTimestamp().isAfter(sevenDaysAgo))
            .map(e -> getEpisodeDuration(e, showsByFolderName))
            .reduce(Duration.ZERO, Duration::plus);
    var lastWeekYoutubeDuration =
        youtubeVideos.stream()
            .filter(v -> v.getTimestamp().isAfter(sevenDaysAgo))
            .map(ImmersionTrackerItem::getYoutubeVideoDuration)
            .reduce(Duration.ZERO, Duration::plus);
    var lastWeekSpotifyDuration =
        spotifyEpisodes.stream()
            .filter(e -> e.getTimestamp().isAfter(sevenDaysAgo))
            .map(ImmersionTrackerItem::getSpotifyEpisodeDuration)
            .reduce(Duration.ZERO, Duration::plus);
    var lastWeekMovieDuration =
        movies.stream()
            .filter(m -> m.getTimestamp().isAfter(sevenDaysAgo))
            .map(ImmersionTrackerItem::getMovieDuration)
            .reduce(Duration.ZERO, Duration::plus);
    var lastWeekDuration =
        lastWeekEpisodeDuration
            .plus(lastWeekYoutubeDuration)
            .plus(lastWeekSpotifyDuration)
            .plus(lastWeekMovieDuration);

    var totalEpisodeDuration =
        episodes.stream()
            .map(e -> getEpisodeDuration(e, showsByFolderName))
            .reduce(Duration.ZERO, Duration::plus);
    var totalYoutubeDuration =
        youtubeVideos.stream()
            .map(ImmersionTrackerItem::getYoutubeVideoDuration)
            .reduce(Duration.ZERO, Duration::plus);
    var totalSpotifyDuration =
        spotifyEpisodes.stream()
            .map(ImmersionTrackerItem::getSpotifyEpisodeDuration)
            .reduce(Duration.ZERO, Duration::plus);
    var totalMovieDuration =
        movies.stream()
            .map(ImmersionTrackerItem::getMovieDuration)
            .reduce(Duration.ZERO, Duration::plus);
    var totalDuration =
        totalEpisodeDuration
            .plus(totalYoutubeDuration)
            .plus(totalSpotifyDuration)
            .plus(totalMovieDuration);

    var totalMinutesWatchedLastWeek = lastWeekDuration.toMinutes();
    var totalMinutesWatched = totalDuration.toMinutes();
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

  private List<SpotifyShow> spotifyShows(
      List<ImmersionTrackerItem> spotifyEpisodes, List<ImmersionTrackerItem> spotifyShows) {
    var showsByShowId =
        spotifyShows.stream()
            .collect(Collectors.toMap(ImmersionTrackerItem::getSpotifyShowId, v -> v));
    var episodesByShowId =
        spotifyEpisodes.stream()
            .collect(Collectors.groupingBy(ImmersionTrackerItem::getSpotifyShowId));
    return episodesByShowId.entrySet().stream()
        .map(
            entry -> {
              var showId = entry.getKey();
              var episodes = entry.getValue();
              var show = showsByShowId.get(showId);
              var showName = show != null ? show.getSpotifyShowName() : null;
              return new SpotifyShow(showName, episodes.size());
            })
        .sorted(
            Comparator.comparing((SpotifyShow s) -> s.episodesWatched, Comparator.reverseOrder())
                .thenComparing(s -> s.showName, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  private List<DailyActivity> dailyActivity(
      List<ImmersionTrackerItem> episodes,
      Map<String, ImmersionTrackerItem> showsByFolderName,
      List<ImmersionTrackerItem> youtubeVideos,
      List<ImmersionTrackerItem> spotifyEpisodes,
      List<ImmersionTrackerItem> movies,
      Instant now) {
    var result = new ArrayList<DailyActivity>();
    for (int daysAgo = 6; daysAgo >= 0; daysAgo--) {
      var dayStart =
          now.atZone(ZONE_ID).truncatedTo(ChronoUnit.DAYS).minusDays(daysAgo).toInstant();
      var dayEnd = dayStart.plus(1, ChronoUnit.DAYS);

      var episodeDuration =
          episodes.stream()
              .filter(
                  e -> !e.getTimestamp().isBefore(dayStart) && e.getTimestamp().isBefore(dayEnd))
              .map(e -> getEpisodeDuration(e, showsByFolderName))
              .reduce(Duration.ZERO, Duration::plus);
      var youtubeDuration =
          youtubeVideos.stream()
              .filter(
                  v -> !v.getTimestamp().isBefore(dayStart) && v.getTimestamp().isBefore(dayEnd))
              .map(ImmersionTrackerItem::getYoutubeVideoDuration)
              .reduce(Duration.ZERO, Duration::plus);
      var spotifyDuration =
          spotifyEpisodes.stream()
              .filter(
                  e -> !e.getTimestamp().isBefore(dayStart) && e.getTimestamp().isBefore(dayEnd))
              .map(ImmersionTrackerItem::getSpotifyEpisodeDuration)
              .reduce(Duration.ZERO, Duration::plus);
      var movieDuration =
          movies.stream()
              .filter(
                  m -> !m.getTimestamp().isBefore(dayStart) && m.getTimestamp().isBefore(dayEnd))
              .map(ImmersionTrackerItem::getMovieDuration)
              .reduce(Duration.ZERO, Duration::plus);

      var dayDuration =
          episodeDuration.plus(youtubeDuration).plus(spotifyDuration).plus(movieDuration);
      result.add(new DailyActivity(daysAgo, (int) dayDuration.toMinutes()));
    }
    return result;
  }

  @VisibleForTesting
  List<CumulativeProgress> allTimeProgress(
      List<ImmersionTrackerItem> episodes,
      Map<String, ImmersionTrackerItem> showsByFolderName,
      List<ImmersionTrackerItem> youtubeVideos,
      List<ImmersionTrackerItem> spotifyEpisodes,
      List<ImmersionTrackerItem> movies) {
    var durationsByQuarter = new TreeMap<LocalDate, Duration>();

    for (var episode : episodes) {
      addProgress(
          durationsByQuarter,
          episode.getTimestamp(),
          getEpisodeDuration(episode, showsByFolderName));
    }

    for (var video : youtubeVideos) {
      addProgress(durationsByQuarter, video.getTimestamp(), video.getYoutubeVideoDuration());
    }

    for (var episode : spotifyEpisodes) {
      addProgress(durationsByQuarter, episode.getTimestamp(), episode.getSpotifyEpisodeDuration());
    }

    for (var movie : movies) {
      addProgress(durationsByQuarter, movie.getTimestamp(), movie.getMovieDuration());
    }

    if (durationsByQuarter.isEmpty()) {
      return List.of();
    }

    var labelFormatter = DateTimeFormatter.ofPattern("MMM uuuu", Locale.ENGLISH);
    var result = new ArrayList<CumulativeProgress>();
    var cumulativeDuration = Duration.ZERO;
    var cursor = durationsByQuarter.firstKey();
    var lastQuarter = durationsByQuarter.lastKey();
    while (!cursor.isAfter(lastQuarter)) {
      var duration = durationsByQuarter.getOrDefault(cursor, Duration.ZERO);
      cumulativeDuration = cumulativeDuration.plus(duration);
      var cumulativeHours = (int) cumulativeDuration.toHours();
      var label = cursor.format(labelFormatter);
      result.add(new CumulativeProgress(label, cumulativeHours));
      cursor = cursor.plusMonths(3);
    }
    return result;
  }

  private static void addProgress(
      Map<LocalDate, Duration> durationsByQuarter, Instant timestamp, Duration duration) {
    var zoned = timestamp.atZone(ZONE_ID);
    var zeroBasedMonth = zoned.getMonthValue() - 1;
    var quarterMonth = zeroBasedMonth - zeroBasedMonth % 3 + 1;
    var quarterStart = LocalDate.of(zoned.getYear(), quarterMonth, 1);
    durationsByQuarter.merge(quarterStart, duration, Duration::plus);
  }

  private List<Movie> movies(List<ImmersionTrackerItem> movies) {
    return movies.stream()
        .map(m -> new Movie(m.getTvdbName()))
        .sorted(Comparator.comparing(m -> m.name, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  private Duration getEpisodeDuration(
      ImmersionTrackerItem episode, Map<String, ImmersionTrackerItem> showsByFolderName) {
    var show = showsByFolderName.get(episode.getFolderName());
    var showDuration = show != null ? show.getTvdbAverageRuntime() : null;
    return showDuration != null ? showDuration : DEFAULT_EPISODE_DURATION;
  }
}
