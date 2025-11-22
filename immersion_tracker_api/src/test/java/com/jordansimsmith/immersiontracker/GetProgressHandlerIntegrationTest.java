package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class GetProgressHandlerIntegrationTest {
  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;

  private GetProgressHandler getProgressHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = ImmersionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());
    var table = factory.immersionTrackerTable();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory = ImmersionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    objectMapper = factory.objectMapper();
    immersionTrackerTable = factory.immersionTrackerTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    getProgressHandler = new GetProgressHandler(factory);
  }

  @Test
  void handleRequestShouldCalculateProgress() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.ofEpochMilli(100_000_000));
    var now = fakeClock.now().atZone(GetProgressHandler.ZONE_ID).toInstant();
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", Instant.EPOCH);
    var episode2 = ImmersionTrackerItem.createEpisode(user, "show2", "episode2", now);
    var episode3 = ImmersionTrackerItem.createEpisode(user, "show3", "episode1", now);
    var show1 = ImmersionTrackerItem.createShow(user, "show1");
    show1.setTvdbId(1);
    show1.setTvdbName("my show");
    var show2 = ImmersionTrackerItem.createShow(user, "show2");
    show2.setTvdbId(1);
    show2.setTvdbName("my show");
    var show3 = ImmersionTrackerItem.createShow(user, "show3");
    show3.setTvdbId(2);
    show3.setTvdbName("my other show");

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(episode3);
    immersionTrackerTable.putItem(show1);
    immersionTrackerTable.putItem(show2);
    immersionTrackerTable.putItem(show3);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    assertThat(res.getHeaders()).containsEntry("Content-Type", "application/json; charset=utf-8");

    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);
    assertThat(progress).isNotNull();
    assertThat(progress.totalEpisodesWatched()).isEqualTo(3);
    assertThat(progress.totalHoursWatched()).isEqualTo(1);
    assertThat(progress.episodesWatchedToday()).isEqualTo(2);
    assertThat(progress.youtubeVideosWatchedToday()).isEqualTo(0);
    assertThat(progress.daysSinceFirstEpisode()).isEqualTo(1);

    var shows = progress.shows();
    assertThat(shows).hasSize(2);
    assertThat(shows.get(0).name()).isEqualTo("my show");
    assertThat(shows.get(0).episodesWatched()).isEqualTo(2);
    assertThat(shows.get(1).name()).isEqualTo("my other show");
    assertThat(shows.get(1).episodesWatched()).isEqualTo(1);
  }

  @Test
  void handleRequestShouldUseShowAverageRuntimeWhenAvailable() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.ofEpochMilli(200_000));
    var now = fakeClock.now();
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", now);
    var episode2 = ImmersionTrackerItem.createEpisode(user, "show1", "episode2", now);
    var episode3 = ImmersionTrackerItem.createEpisode(user, "show1", "episode3", now);
    var show = ImmersionTrackerItem.createShow(user, "show1");
    show.setTvdbId(1);
    show.setTvdbName("my show");
    show.setTvdbAverageRuntime(Duration.ofMinutes(45));

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(episode3);
    immersionTrackerTable.putItem(show);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);
    assertThat(progress.totalEpisodesWatched()).isEqualTo(3);
    assertThat(progress.totalHoursWatched()).isEqualTo(2); // 3 * 45 minutes = 135 -> 2 hours
    var today =
        progress.dailyActivity().stream()
            .filter(activity -> activity.daysAgo() == 0)
            .findFirst()
            .orElseThrow();
    assertThat(today.minutesWatched()).isEqualTo(135);
  }

  @Test
  void handleRequestShouldReturnUnknownShow() throws Exception {
    // arrange
    var user = "alice";
    var now = fakeClock.now().atZone(GetProgressHandler.ZONE_ID).toInstant();
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", now);
    var episode2 = ImmersionTrackerItem.createEpisode(user, "show1", "episode2", now);
    var episode3 = ImmersionTrackerItem.createEpisode(user, "show3", "episode1", now);
    var show1 = ImmersionTrackerItem.createShow(user, "show1");
    show1.setTvdbId(1);
    show1.setTvdbName("my show");

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(episode3);
    immersionTrackerTable.putItem(show1);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);
    assertThat(res.getHeaders()).containsEntry("Content-Type", "application/json; charset=utf-8");

    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);
    assertThat(progress).isNotNull();
    var shows = progress.shows();
    assertThat(shows).hasSize(2);
    assertThat(shows.get(0).name()).isEqualTo("my show");
    assertThat(shows.get(0).episodesWatched()).isEqualTo(2);
    assertThat(shows.get(1).name()).isNull();
    assertThat(shows.get(1).episodesWatched()).isEqualTo(1);
  }

  @Test
  void handleRequestShouldReturnPositiveWeeklyTrendWhenRecentViewingAboveAverage()
      throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.EPOCH.plus(28, ChronoUnit.DAYS));
    var now = fakeClock.now();
    var sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);

    // 4 episodes over 28 days = 1 episode per week average
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", Instant.EPOCH);
    var episode2 =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode2", Instant.EPOCH.plus(14, ChronoUnit.DAYS));
    var episode3 =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode3", sevenDaysAgo.plus(1, ChronoUnit.DAYS));
    var episode4 =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode4", now.minus(1, ChronoUnit.DAYS));

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(episode3);
    immersionTrackerTable.putItem(episode4);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);
    // 2 episodes in last 7 days vs 1 per week average = 100% increase
    assertThat(progress.weeklyTrendPercentage()).isEqualTo(100.0);
  }

  @Test
  void handleRequestShouldReturnNegativeWeeklyTrendWhenRecentViewingBelowAverage()
      throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.EPOCH.plus(14, ChronoUnit.DAYS));

    // 4 episodes over 14 days = 2 episodes per week average, but 0 in last 7 days
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", Instant.EPOCH);
    var episode2 =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode2", Instant.EPOCH.plus(1, ChronoUnit.DAYS));
    var episode3 =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode3", Instant.EPOCH.plus(2, ChronoUnit.DAYS));
    var episode4 =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode4", Instant.EPOCH.plus(3, ChronoUnit.DAYS));

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(episode3);
    immersionTrackerTable.putItem(episode4);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);
    // 0 episodes in last 7 days vs 2 per week average = -100% decrease
    assertThat(progress.weeklyTrendPercentage()).isEqualTo(-100.0);
  }

  @Test
  void handleRequestShouldIncludeYoutubeVideosInProgress() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.ofEpochMilli(123_000));
    var now = fakeClock.now();

    // Create episodes (20 minutes each)
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", now);
    var episode2 = ImmersionTrackerItem.createEpisode(user, "show1", "episode2", now);

    // Create YouTube videos
    var youtubeVideo1 =
        ImmersionTrackerItem.createYoutubeVideo(
            user, "UCChannel1", "video1", "YouTube Video 1", Duration.ofMinutes(10), now);
    var youtubeVideo2 =
        ImmersionTrackerItem.createYoutubeVideo(
            user, "UCChannel2", "video2", "YouTube Video 2", Duration.ofMinutes(30), now);

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(youtubeVideo1);
    immersionTrackerTable.putItem(youtubeVideo2);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);

    // Episodes: 2 * 20 minutes = 40 minutes
    // YouTube: 10 + 30 = 40 minutes
    // Total: 80 minutes = 1 hour (rounded down)
    assertThat(progress.totalHoursWatched()).isEqualTo(1);
    assertThat(progress.totalEpisodesWatched()).isEqualTo(2);
    assertThat(progress.youtubeVideosWatched()).isEqualTo(2);
    assertThat(progress.youtubeVideosWatchedToday()).isEqualTo(2);
    assertThat(progress.episodesWatchedToday()).isEqualTo(2);
  }

  @Test
  void handleRequestShouldIncludeYoutubeVideosInWeeklyTrend() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.EPOCH.plus(28, ChronoUnit.DAYS));
    var now = fakeClock.now();
    var sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);

    // Historical content: 2 episodes (40 minutes) over 28 days
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", Instant.EPOCH);
    var episode2 =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode2", Instant.EPOCH.plus(14, ChronoUnit.DAYS));

    // Recent content in last 7 days: 1 episode (20 minutes) + 1 YouTube video (30 minutes) = 50
    // minutes
    var recentEpisode =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode3", sevenDaysAgo.plus(1, ChronoUnit.DAYS));
    var recentYoutubeVideo =
        ImmersionTrackerItem.createYoutubeVideo(
            user,
            "UCChannel1",
            "video1",
            "Recent Video",
            Duration.ofMinutes(30),
            now.minus(1, ChronoUnit.DAYS));

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(recentEpisode);
    immersionTrackerTable.putItem(recentYoutubeVideo);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);

    // Total minutes: 3 episodes * 20 + 1 YouTube * 30 = 90 minutes
    // Average per week over 28 days: 90 / 28 * 7 = 22.5 minutes/week
    // Last week: 20 + 30 = 50 minutes
    // Weekly trend: (50 - 22.5) / 22.5 * 100 = 122.22%
    assertThat(progress.weeklyTrendPercentage()).isCloseTo(122.22, within(0.1));
  }

  @Test
  void handleRequestShouldCalculateWeeklyTrendWithOnlyYoutubeVideos() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.EPOCH.plus(21, ChronoUnit.DAYS));
    var now = fakeClock.now();
    var sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);

    // Historical YouTube videos: 60 minutes over 21 days
    var oldVideo1 =
        ImmersionTrackerItem.createYoutubeVideo(
            user, "UCChannel1", "video1", "Old Video 1", Duration.ofMinutes(30), Instant.EPOCH);
    var oldVideo2 =
        ImmersionTrackerItem.createYoutubeVideo(
            user,
            "UCChannel1",
            "video2",
            "Old Video 2",
            Duration.ofMinutes(30),
            Instant.EPOCH.plus(7, ChronoUnit.DAYS));

    // Recent YouTube videos in last 7 days: 40 minutes
    var recentVideo1 =
        ImmersionTrackerItem.createYoutubeVideo(
            user,
            "UCChannel1",
            "video3",
            "Recent Video 1",
            Duration.ofMinutes(25),
            sevenDaysAgo.plus(1, ChronoUnit.DAYS));
    var recentVideo2 =
        ImmersionTrackerItem.createYoutubeVideo(
            user,
            "UCChannel1",
            "video4",
            "Recent Video 2",
            Duration.ofMinutes(15),
            now.minus(1, ChronoUnit.DAYS));

    immersionTrackerTable.putItem(oldVideo1);
    immersionTrackerTable.putItem(oldVideo2);
    immersionTrackerTable.putItem(recentVideo1);
    immersionTrackerTable.putItem(recentVideo2);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);

    // Total minutes: 100 minutes YouTube
    // Average per week over 21 days: 100 / 21 * 7 = 33.33 minutes/week
    // Last week: 40 minutes
    // Weekly trend: (40 - 33.33) / 33.33 * 100 = 20%
    assertThat(progress.weeklyTrendPercentage()).isCloseTo(20.0, within(0.1));
  }

  @Test
  void handleRequestShouldGroupYoutubeVideosByChannel() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.ofEpochMilli(123_000));
    var now = fakeClock.now();

    var video1 =
        ImmersionTrackerItem.createYoutubeVideo(
            user, "UCChannel1", "video1", "Video 1", Duration.ofMinutes(10), now);
    var video2 =
        ImmersionTrackerItem.createYoutubeVideo(
            user, "UCChannel1", "video2", "Video 2", Duration.ofMinutes(15), now);
    var video3 =
        ImmersionTrackerItem.createYoutubeVideo(
            user, "UCChannel2", "video3", "Video 3", Duration.ofMinutes(20), now);
    var video4 =
        ImmersionTrackerItem.createYoutubeVideo(
            user, "UCChannel3", "video4", "Video 4", Duration.ofMinutes(5), now);
    var video5 =
        ImmersionTrackerItem.createYoutubeVideo(
            user, "UCChannel1", "video5", "Video 5", Duration.ofMinutes(8), now);

    var channel1 = ImmersionTrackerItem.createYoutubeChannel(user, "UCChannel1", "Channel One");
    var channel2 = ImmersionTrackerItem.createYoutubeChannel(user, "UCChannel2", "Channel Two");
    var channel3 = ImmersionTrackerItem.createYoutubeChannel(user, "UCChannel3", "Channel Three");

    immersionTrackerTable.putItem(video1);
    immersionTrackerTable.putItem(video2);
    immersionTrackerTable.putItem(video3);
    immersionTrackerTable.putItem(video4);
    immersionTrackerTable.putItem(video5);
    immersionTrackerTable.putItem(channel1);
    immersionTrackerTable.putItem(channel2);
    immersionTrackerTable.putItem(channel3);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);

    var channels = progress.youtubeChannels();
    assertThat(channels).hasSize(3);
    assertThat(channels.get(0).channelName()).isEqualTo("Channel One");
    assertThat(channels.get(0).videosWatched()).isEqualTo(3);
    assertThat(channels.get(1).channelName()).isEqualTo("Channel Three");
    assertThat(channels.get(1).videosWatched()).isEqualTo(1);
    assertThat(channels.get(2).channelName()).isEqualTo("Channel Two");
    assertThat(channels.get(2).videosWatched()).isEqualTo(1);
  }

  @Test
  void handleRequestShouldReturnUnknownChannelWhenChannelItemMissing() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.ofEpochMilli(123_000));
    var now = fakeClock.now();

    var video1 =
        ImmersionTrackerItem.createYoutubeVideo(
            user, "UCChannel1", "video1", "Video 1", Duration.ofMinutes(10), now);
    var video2 =
        ImmersionTrackerItem.createYoutubeVideo(
            user, "UCChannel2", "video2", "Video 2", Duration.ofMinutes(15), now);
    var video3 =
        ImmersionTrackerItem.createYoutubeVideo(
            user, "UCChannel2", "video3", "Video 3", Duration.ofMinutes(20), now);

    var channel2 = ImmersionTrackerItem.createYoutubeChannel(user, "UCChannel2", "Channel Two");

    immersionTrackerTable.putItem(video1);
    immersionTrackerTable.putItem(video2);
    immersionTrackerTable.putItem(video3);
    immersionTrackerTable.putItem(channel2);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);

    var channels = progress.youtubeChannels();
    assertThat(channels).hasSize(2);
    assertThat(channels.get(0).channelName()).isEqualTo("Channel Two");
    assertThat(channels.get(0).videosWatched()).isEqualTo(2);
    assertThat(channels.get(1).channelName()).isNull();
    assertThat(channels.get(1).videosWatched()).isEqualTo(1);
  }

  @Test
  void handleRequestShouldCalculateDailyActivity() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.parse("2024-01-07T12:00:00Z"));
    var now = fakeClock.now();
    var todayStart =
        now.atZone(GetProgressHandler.ZONE_ID).truncatedTo(ChronoUnit.DAYS).toInstant();

    // 6 days ago: 1 episode (20 minutes)
    var sixDaysAgo = todayStart.minus(6, ChronoUnit.DAYS).plus(6, ChronoUnit.HOURS);
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", sixDaysAgo);

    // 5 days ago: nothing

    // 4 days ago: 2 episodes (40 minutes)
    var fourDaysAgo = todayStart.minus(4, ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS);
    var episode2 = ImmersionTrackerItem.createEpisode(user, "show1", "episode2", fourDaysAgo);
    var episode3 = ImmersionTrackerItem.createEpisode(user, "show1", "episode3", fourDaysAgo);

    // 3 days ago: 1 episode + 1 YouTube video (20 + 30 = 50 minutes)
    var threeDaysAgo = todayStart.minus(3, ChronoUnit.DAYS).plus(18, ChronoUnit.HOURS);
    var episode4 = ImmersionTrackerItem.createEpisode(user, "show1", "episode4", threeDaysAgo);
    var video1 =
        ImmersionTrackerItem.createYoutubeVideo(
            user, "UCChannel1", "video1", "Video 1", Duration.ofMinutes(30), threeDaysAgo);

    // 2 days ago: 1 YouTube video (45 minutes)
    var twoDaysAgo = todayStart.minus(2, ChronoUnit.DAYS).plus(8, ChronoUnit.HOURS);
    var video2 =
        ImmersionTrackerItem.createYoutubeVideo(
            user, "UCChannel1", "video2", "Video 2", Duration.ofMinutes(45), twoDaysAgo);

    // yesterday: 1 episode (20 minutes)
    var yesterday = todayStart.minus(1, ChronoUnit.DAYS).plus(14, ChronoUnit.HOURS);
    var episode5 = ImmersionTrackerItem.createEpisode(user, "show1", "episode5", yesterday);

    // today: 1 episode + 1 YouTube video (20 + 10 = 30 minutes)
    var today = todayStart.plus(10, ChronoUnit.HOURS);
    var episode6 = ImmersionTrackerItem.createEpisode(user, "show1", "episode6", today);
    var video3 =
        ImmersionTrackerItem.createYoutubeVideo(
            user, "UCChannel1", "video3", "Video 3", Duration.ofMinutes(10), today);

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(episode3);
    immersionTrackerTable.putItem(episode4);
    immersionTrackerTable.putItem(episode5);
    immersionTrackerTable.putItem(episode6);
    immersionTrackerTable.putItem(video1);
    immersionTrackerTable.putItem(video2);
    immersionTrackerTable.putItem(video3);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);

    var dailyActivity = progress.dailyActivity();
    assertThat(dailyActivity).hasSize(7);
    assertThat(dailyActivity.get(0).daysAgo()).isEqualTo(6);
    assertThat(dailyActivity.get(0).minutesWatched()).isEqualTo(20);
    assertThat(dailyActivity.get(1).daysAgo()).isEqualTo(5);
    assertThat(dailyActivity.get(1).minutesWatched()).isEqualTo(0);
    assertThat(dailyActivity.get(2).daysAgo()).isEqualTo(4);
    assertThat(dailyActivity.get(2).minutesWatched()).isEqualTo(40);
    assertThat(dailyActivity.get(3).daysAgo()).isEqualTo(3);
    assertThat(dailyActivity.get(3).minutesWatched()).isEqualTo(50);
    assertThat(dailyActivity.get(4).daysAgo()).isEqualTo(2);
    assertThat(dailyActivity.get(4).minutesWatched()).isEqualTo(45);
    assertThat(dailyActivity.get(5).daysAgo()).isEqualTo(1);
    assertThat(dailyActivity.get(5).minutesWatched()).isEqualTo(20);
    assertThat(dailyActivity.get(6).daysAgo()).isEqualTo(0);
    assertThat(dailyActivity.get(6).minutesWatched()).isEqualTo(30);
  }

  @Test
  void handleRequestShouldIncludeSpotifyEpisodesInProgress() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.ofEpochMilli(123_000));
    var now = fakeClock.now();

    // Create episodes (20 minutes each)
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", now);
    var episode2 = ImmersionTrackerItem.createEpisode(user, "show1", "episode2", now);

    // Create Spotify episodes
    var spotifyEpisode1 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "testShowId1", "episode1", "Spotify Episode 1", Duration.ofMinutes(15), now);
    var spotifyEpisode2 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "testShowId2", "episode2", "Spotify Episode 2", Duration.ofMinutes(45), now);

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(spotifyEpisode1);
    immersionTrackerTable.putItem(spotifyEpisode2);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);

    // Episodes: 2 * 20 minutes = 40 minutes
    // Spotify: 15 + 45 = 60 minutes
    // Total: 100 minutes = 1 hour (rounded down)
    assertThat(progress.totalHoursWatched()).isEqualTo(1);
    assertThat(progress.totalEpisodesWatched()).isEqualTo(2);
    assertThat(progress.spotifyEpisodesWatched()).isEqualTo(2);
    assertThat(progress.spotifyEpisodesWatchedToday()).isEqualTo(2);
    assertThat(progress.episodesWatchedToday()).isEqualTo(2);
  }

  @Test
  void handleRequestShouldIncludeSpotifyEpisodesInWeeklyTrend() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.EPOCH.plus(28, ChronoUnit.DAYS));
    var now = fakeClock.now();
    var sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);

    // Historical content: 2 episodes (40 minutes) over 28 days
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", Instant.EPOCH);
    var episode2 =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode2", Instant.EPOCH.plus(14, ChronoUnit.DAYS));

    // Recent content in last 7 days: 1 episode (20 minutes) + 1 Spotify episode (30 minutes) = 50
    // minutes
    var recentEpisode =
        ImmersionTrackerItem.createEpisode(
            user, "show1", "episode3", sevenDaysAgo.plus(1, ChronoUnit.DAYS));
    var recentSpotifyEpisode =
        ImmersionTrackerItem.createSpotifyEpisode(
            user,
            "testShowId",
            "episode1",
            "Recent Episode",
            Duration.ofMinutes(30),
            now.minus(1, ChronoUnit.DAYS));

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(recentEpisode);
    immersionTrackerTable.putItem(recentSpotifyEpisode);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);

    // Total minutes: 3 episodes * 20 + 1 Spotify * 30 = 90 minutes
    // Average per week over 28 days: 90 / 28 * 7 = 22.5 minutes/week
    // Last week: 20 + 30 = 50 minutes
    // Weekly trend: (50 - 22.5) / 22.5 * 100 = 122.22%
    assertThat(progress.weeklyTrendPercentage()).isCloseTo(122.22, within(0.1));
  }

  @Test
  void handleRequestShouldCalculateWeeklyTrendWithOnlySpotifyEpisodes() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.EPOCH.plus(21, ChronoUnit.DAYS));
    var now = fakeClock.now();
    var sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);

    // Historical Spotify episodes: 60 minutes over 21 days
    var oldEpisode1 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "testShowId", "episode1", "Old Episode 1", Duration.ofMinutes(30), Instant.EPOCH);
    var oldEpisode2 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user,
            "testShowId",
            "episode2",
            "Old Episode 2",
            Duration.ofMinutes(30),
            Instant.EPOCH.plus(7, ChronoUnit.DAYS));

    // Recent Spotify episodes in last 7 days: 40 minutes
    var recentEpisode1 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user,
            "testShowId",
            "episode3",
            "Recent Episode 1",
            Duration.ofMinutes(25),
            sevenDaysAgo.plus(1, ChronoUnit.DAYS));
    var recentEpisode2 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user,
            "testShowId",
            "episode4",
            "Recent Episode 2",
            Duration.ofMinutes(15),
            now.minus(1, ChronoUnit.DAYS));

    immersionTrackerTable.putItem(oldEpisode1);
    immersionTrackerTable.putItem(oldEpisode2);
    immersionTrackerTable.putItem(recentEpisode1);
    immersionTrackerTable.putItem(recentEpisode2);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);

    // Total minutes: 100 minutes Spotify
    // Average per week over 21 days: 100 / 21 * 7 = 33.33 minutes/week
    // Last week: 40 minutes
    // Weekly trend: (40 - 33.33) / 33.33 * 100 = 20%
    assertThat(progress.weeklyTrendPercentage()).isCloseTo(20.0, within(0.1));
  }

  @Test
  void handleRequestShouldGroupSpotifyEpisodesByShow() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.ofEpochMilli(123_000));
    var now = fakeClock.now();

    var episode1 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "testShowId1", "episode1", "Episode 1", Duration.ofMinutes(10), now);
    var episode2 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "testShowId1", "episode2", "Episode 2", Duration.ofMinutes(15), now);
    var episode3 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "testShowId2", "episode3", "Episode 3", Duration.ofMinutes(20), now);
    var episode4 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "testShowId3", "episode4", "Episode 4", Duration.ofMinutes(5), now);
    var episode5 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "testShowId1", "episode5", "Episode 5", Duration.ofMinutes(8), now);

    var show1 = ImmersionTrackerItem.createSpotifyShow(user, "testShowId1", "Show One");
    var show2 = ImmersionTrackerItem.createSpotifyShow(user, "testShowId2", "Show Two");
    var show3 = ImmersionTrackerItem.createSpotifyShow(user, "testShowId3", "Show Three");

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(episode3);
    immersionTrackerTable.putItem(episode4);
    immersionTrackerTable.putItem(episode5);
    immersionTrackerTable.putItem(show1);
    immersionTrackerTable.putItem(show2);
    immersionTrackerTable.putItem(show3);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);

    var shows = progress.spotifyShows();
    assertThat(shows).hasSize(3);
    assertThat(shows.get(0).showName()).isEqualTo("Show One");
    assertThat(shows.get(0).episodesWatched()).isEqualTo(3);
    assertThat(shows.get(1).showName()).isEqualTo("Show Three");
    assertThat(shows.get(1).episodesWatched()).isEqualTo(1);
    assertThat(shows.get(2).showName()).isEqualTo("Show Two");
    assertThat(shows.get(2).episodesWatched()).isEqualTo(1);
  }

  @Test
  void handleRequestShouldHandleSpotifyShowsWithoutShowMetadata() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.ofEpochMilli(123_000));
    var now = fakeClock.now();

    var episode1 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "testShowId1", "episode1", "Episode 1", Duration.ofMinutes(10), now);
    var episode2 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "testShowId2", "episode2", "Episode 2", Duration.ofMinutes(15), now);
    var episode3 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "testShowId2", "episode3", "Episode 3", Duration.ofMinutes(20), now);

    var show2 = ImmersionTrackerItem.createSpotifyShow(user, "testShowId2", "Show Two");

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(episode3);
    immersionTrackerTable.putItem(show2);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);

    var shows = progress.spotifyShows();
    assertThat(shows).hasSize(2);
    assertThat(shows.get(0).showName()).isEqualTo("Show Two");
    assertThat(shows.get(0).episodesWatched()).isEqualTo(2);
    assertThat(shows.get(1).showName()).isNull();
    assertThat(shows.get(1).episodesWatched()).isEqualTo(1);
  }

  @Test
  void handleRequestShouldIncludeSpotifyEpisodesInDailyActivity() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.parse("2024-01-07T12:00:00Z"));
    var now = fakeClock.now();
    var todayStart =
        now.atZone(GetProgressHandler.ZONE_ID).truncatedTo(ChronoUnit.DAYS).toInstant();

    // 3 days ago: 1 episode + 1 Spotify episode (20 + 30 = 50 minutes)
    var threeDaysAgo = todayStart.minus(3, ChronoUnit.DAYS).plus(18, ChronoUnit.HOURS);
    var episode1 = ImmersionTrackerItem.createEpisode(user, "show1", "episode1", threeDaysAgo);
    var spotifyEpisode1 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user,
            "testShowId",
            "episode1",
            "Spotify Episode 1",
            Duration.ofMinutes(30),
            threeDaysAgo);

    // 2 days ago: 1 Spotify episode (45 minutes)
    var twoDaysAgo = todayStart.minus(2, ChronoUnit.DAYS).plus(8, ChronoUnit.HOURS);
    var spotifyEpisode2 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user,
            "testShowId",
            "episode2",
            "Spotify Episode 2",
            Duration.ofMinutes(45),
            twoDaysAgo);

    // today: 1 episode + 1 Spotify episode (20 + 10 = 30 minutes)
    var today = todayStart.plus(10, ChronoUnit.HOURS);
    var episode2 = ImmersionTrackerItem.createEpisode(user, "show1", "episode2", today);
    var spotifyEpisode3 =
        ImmersionTrackerItem.createSpotifyEpisode(
            user, "testShowId", "episode3", "Spotify Episode 3", Duration.ofMinutes(10), today);

    immersionTrackerTable.putItem(episode1);
    immersionTrackerTable.putItem(episode2);
    immersionTrackerTable.putItem(spotifyEpisode1);
    immersionTrackerTable.putItem(spotifyEpisode2);
    immersionTrackerTable.putItem(spotifyEpisode3);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);

    var dailyActivity = progress.dailyActivity();
    assertThat(dailyActivity).hasSize(7);
    assertThat(dailyActivity.get(3).daysAgo()).isEqualTo(3);
    assertThat(dailyActivity.get(3).minutesWatched()).isEqualTo(50);
    assertThat(dailyActivity.get(4).daysAgo()).isEqualTo(2);
    assertThat(dailyActivity.get(4).minutesWatched()).isEqualTo(45);
    assertThat(dailyActivity.get(6).daysAgo()).isEqualTo(0);
    assertThat(dailyActivity.get(6).minutesWatched()).isEqualTo(30);
  }

  @Test
  void handleRequestShouldIncludeMoviesInProgress() throws Exception {
    // arrange
    var user = "alice";
    fakeClock.setTime(Instant.ofEpochMilli(123_000));
    var now = fakeClock.now();

    var movie1 =
        ImmersionTrackerItem.createMovie(
            user, "movie1", 1, "My Movie", "image1", Duration.ofMinutes(120), now);
    var movie2 =
        ImmersionTrackerItem.createMovie(
            user, "movie2", 2, "Another Movie", "image2", Duration.ofMinutes(30), now);

    immersionTrackerTable.putItem(movie1);
    immersionTrackerTable.putItem(movie2);

    // act
    var req =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("user", user)).build();
    var res = getProgressHandler.handleRequest(req, null);

    // assert
    assertThat(res.getStatusCode()).isEqualTo(200);

    var progress =
        objectMapper.readValue(res.getBody(), GetProgressHandler.GetProgressResponse.class);

    // Movies: 120 + 30 minutes = 150 minutes = 2 hours (rounded down)
    assertThat(progress.totalHoursWatched()).isEqualTo(2);
    assertThat(progress.totalMoviesWatched()).isEqualTo(2);
    assertThat(progress.moviesWatchedToday()).isEqualTo(2);

    var movies = progress.movies();
    assertThat(movies).hasSize(2);
    assertThat(movies)
        .extracting(GetProgressHandler.Movie::name)
        .containsExactly("Another Movie", "My Movie");
  }
}
