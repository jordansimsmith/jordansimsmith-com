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

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = ImmersionTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    objectMapper = factory.objectMapper();
    immersionTrackerTable = factory.immersionTrackerTable();

    DynamoDbUtils.createTable(factory.dynamoDbClient(), immersionTrackerTable);

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
}
