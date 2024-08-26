package com.jordansimsmith.immersiontracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class GetProgressHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final int MINUTES_PER_EPISODE = 20;
  @VisibleForTesting static final ZoneId ZONE_ID = ZoneId.of("Pacific/Auckland");

  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;

  @VisibleForTesting
  record ProgressResponse(
      @JsonProperty("total_episodes_watched") int totalEpisodesWatched,
      @JsonProperty("total_hours_watched") int totalHoursWatched,
      @JsonProperty("episodes_watched_today") int episodesWatchedToday,
      @JsonProperty("shows") List<ShowProgress> shows) {}

  @VisibleForTesting
  record ShowProgress(
      @Nullable @JsonProperty("name") String name,
      @JsonProperty("episodes_watched") int episodesWatched) {}

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
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    // TODO: auth
    var user = "jordansimsmith";

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

    var totalEpisodesWatched = episodes.size();
    var totalHoursWatched = totalEpisodesWatched * MINUTES_PER_EPISODE / 60;
    var today = clock.now().atZone(ZONE_ID).truncatedTo(ChronoUnit.DAYS).toInstant();
    var episodesWatchedToday =
        episodes.stream()
            .filter(e -> Instant.ofEpochSecond(e.getTimestamp()).isAfter(today))
            .toList()
            .size();

    var showsByFolderName =
        shows.stream().collect(Collectors.toMap(ImmersionTrackerItem::getFolderName, v -> v));
    var showEpisodes =
        episodes.stream()
            .map(e -> new EpisodeShow(e, showsByFolderName.get(e.getFolderName())))
            .toList();
    var unknownShows = showEpisodes.stream().filter(e -> e.show() == null).toList();
    var unknownShowsProgress = new ShowProgress(null, unknownShows.size());
    var knownShows =
        showEpisodes.stream()
            .filter(e -> e.show() != null)
            .collect(Collectors.groupingBy(EpisodeShow::show));
    var knownShowsProgress =
        knownShows.entrySet().stream()
            .map(e -> new ShowProgress(e.getKey().getTvdbName(), e.getValue().size()));

    var progresses =
        Stream.concat(Stream.of(unknownShowsProgress), knownShowsProgress)
            .sorted(Comparator.comparing(e -> e.episodesWatched, Comparator.reverseOrder()))
            .toList();

    var res =
        new ProgressResponse(
            totalEpisodesWatched, totalHoursWatched, episodesWatchedToday, progresses);

    return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(200)
        .withHeaders(Map.of("Content-Type", "application/json; charset=utf-8"))
        .withBody(objectMapper.writeValueAsString(res))
        .build();
  }
}
