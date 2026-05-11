package com.jordansimsmith.footballcalendar;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.notifications.NotificationPublisher;
import com.jordansimsmith.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

public class UpdateFixturesHandler implements RequestHandler<ScheduledEvent, Void> {
  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateFixturesHandler.class);
  @VisibleForTesting static final String TOPIC = "football_calendar_api_fixture_updates";
  private static final Duration UPCOMING_WINDOW = Duration.ofDays(7);

  private final Clock clock;
  private final NotificationPublisher notificationPublisher;
  private final DynamoDbTable<FootballCalendarItem> footballCalendarTable;
  private final NrfClient nrfClient;
  private final FootballFixClient footballFixClient;
  private final SubfootballClient subfootballClient;
  private final TeamsFactory teamsFactory;

  public UpdateFixturesHandler() {
    this(FootballCalendarFactory.create());
  }

  @VisibleForTesting
  UpdateFixturesHandler(FootballCalendarFactory factory) {
    this.clock = factory.clock();
    this.notificationPublisher = factory.notificationPublisher();
    this.footballCalendarTable = factory.footballCalendarTable();
    this.nrfClient = factory.nrfClient();
    this.footballFixClient = factory.footballFixClient();
    this.subfootballClient = factory.subfootballClient();
    this.teamsFactory = factory.teamsFactory();
  }

  @Override
  public Void handleRequest(ScheduledEvent event, Context context) {
    try {
      return doHandleRequest();
    } catch (Exception e) {
      LOGGER.error("Error processing football fixtures update", e);
      throw new RuntimeException(e);
    }
  }

  private Void doHandleRequest() {
    var now = clock.now();
    var upcomingEnd = now.plus(UPCOMING_WINDOW);
    var changes = new ArrayList<String>();

    // find and combine all fixtures from various sources
    var allFixtures = new ArrayList<FootballCalendarItem>();
    allFixtures.addAll(findNorthernRegionalFootballFixtures());
    allFixtures.addAll(findFootballFixFixtures());
    allFixtures.addAll(findSubfootballFixtures());
    var fixturesByTeam =
        allFixtures.stream().collect(Collectors.groupingBy(FootballCalendarItem::getTeam));

    // process each team separately
    for (var entry : fixturesByTeam.entrySet()) {
      var teamId = entry.getKey();
      var fixtures = entry.getValue();

      // fetch all existing fixtures for this team from DynamoDB
      var existingFixtures =
          footballCalendarTable
              .query(
                  QueryConditional.keyEqualTo(
                      Key.builder().partitionValue(FootballCalendarItem.formatPk(teamId)).build()))
              .items()
              .stream()
              .toList();

      var existingByMatchId =
          existingFixtures.stream()
              .collect(
                  Collectors.toMap(
                      FootballCalendarItem::getMatchId, Function.identity(), (a, b) -> b));
      var newByMatchId =
          fixtures.stream()
              .collect(
                  Collectors.toMap(
                      FootballCalendarItem::getMatchId, Function.identity(), (a, b) -> b));

      // detect upcoming fixture changes
      changes.addAll(detectChanges(existingByMatchId, newByMatchId, teamId, now, upcomingEnd));

      // delete fixtures that no longer exist in the API response
      for (var existingFixture : existingFixtures) {
        if (!newByMatchId.containsKey(existingFixture.getMatchId())) {
          footballCalendarTable.deleteItem(existingFixture);
        }
      }

      // update or add current fixtures
      for (var fixture : fixtures) {
        footballCalendarTable.putItem(fixture);
      }
    }

    if (!changes.isEmpty()) {
      var subject = "Football calendar fixture updated";
      var message = new StringJoiner("\r\n\r\n");
      message.add("The following upcoming fixtures have changed:");
      for (var change : changes) {
        message.add(change);
      }
      notificationPublisher.publish(TOPIC, subject, message.toString());
    }

    return null;
  }

  private List<String> detectChanges(
      Map<String, FootballCalendarItem> existingByMatchId,
      Map<String, FootballCalendarItem> newByMatchId,
      String teamId,
      Instant now,
      Instant upcomingEnd) {
    var changes = new ArrayList<String>();

    // detect added and modified fixtures
    for (var entry : newByMatchId.entrySet()) {
      var matchId = entry.getKey();
      var newFixture = entry.getValue();

      if (!isUpcoming(newFixture.getTimestamp(), now, upcomingEnd)) {
        continue;
      }

      var existing = existingByMatchId.get(matchId);
      if (existing == null) {
        changes.add(
            String.format(
                "ADDED: %s vs %s (%s)\r\n  Time: %s\r\n  Venue: %s",
                newFixture.getHomeTeam(),
                newFixture.getAwayTeam(),
                teamId,
                newFixture.getTimestamp(),
                newFixture.getVenue()));
        continue;
      }

      if (existing.equals(newFixture)) {
        continue;
      }

      var detail = new StringBuilder();
      detail.append(
          String.format(
              "MODIFIED: %s vs %s (%s)",
              newFixture.getHomeTeam(), newFixture.getAwayTeam(), teamId));
      if (!Objects.equals(existing.getTimestamp(), newFixture.getTimestamp())) {
        detail.append(
            String.format(
                "\r\n  Time: %s -> %s", existing.getTimestamp(), newFixture.getTimestamp()));
      }
      if (!Objects.equals(existing.getVenue(), newFixture.getVenue())) {
        detail.append(
            String.format("\r\n  Venue: %s -> %s", existing.getVenue(), newFixture.getVenue()));
      }
      if (!Objects.equals(existing.getAddress(), newFixture.getAddress())) {
        detail.append(
            String.format(
                "\r\n  Address: %s -> %s", existing.getAddress(), newFixture.getAddress()));
      }
      if (!Objects.equals(existing.getStatus(), newFixture.getStatus())) {
        detail.append(
            String.format("\r\n  Status: %s -> %s", existing.getStatus(), newFixture.getStatus()));
      }
      if (!Objects.equals(existing.getHomeTeam(), newFixture.getHomeTeam())) {
        detail.append(
            String.format(
                "\r\n  Home: %s -> %s", existing.getHomeTeam(), newFixture.getHomeTeam()));
      }
      if (!Objects.equals(existing.getAwayTeam(), newFixture.getAwayTeam())) {
        detail.append(
            String.format(
                "\r\n  Away: %s -> %s", existing.getAwayTeam(), newFixture.getAwayTeam()));
      }
      changes.add(detail.toString());
    }

    // detect removed fixtures
    for (var entry : existingByMatchId.entrySet()) {
      var matchId = entry.getKey();
      var existing = entry.getValue();

      if (!isUpcoming(existing.getTimestamp(), now, upcomingEnd)) {
        continue;
      }

      if (!newByMatchId.containsKey(matchId)) {
        changes.add(
            String.format(
                "REMOVED: %s vs %s (%s)\r\n  Was: %s at %s",
                existing.getHomeTeam(),
                existing.getAwayTeam(),
                teamId,
                existing.getTimestamp(),
                existing.getVenue()));
      }
    }

    return changes;
  }

  private boolean isUpcoming(Instant timestamp, Instant now, Instant upcomingEnd) {
    return timestamp != null && !timestamp.isBefore(now) && timestamp.isBefore(upcomingEnd);
  }

  private List<FootballCalendarItem> findNorthernRegionalFootballFixtures() {
    var allFixtures = new ArrayList<FootballCalendarItem>();
    var teams = teamsFactory.findNorthernRegionalFootballTeams();

    for (var team : teams) {
      var from =
          ZonedDateTime.of(team.seasonYear(), 1, 1, 0, 0, 0, 0, ZoneId.systemDefault()).toInstant();
      var to =
          ZonedDateTime.of(team.seasonYear(), 12, 31, 23, 59, 59, 0, ZoneId.systemDefault())
              .toInstant();
      var fixtures =
          nrfClient.findFixtures(
              List.of(team.compId()), List.of(team.orgId()), List.of(team.gradeId()), from, to);

      var teamFixtures =
          fixtures.stream()
              .filter(
                  fixture ->
                      fixture.homeTeamName().toLowerCase().contains(team.nameMatcher())
                          || fixture.awayTeamName().toLowerCase().contains(team.nameMatcher()))
              .toList();

      for (var fixture : teamFixtures) {
        var item =
            FootballCalendarItem.create(
                team.id(),
                fixture.id(),
                fixture.homeTeamName(),
                fixture.awayTeamName(),
                fixture.timestamp(),
                fixture.venue(),
                fixture.address(),
                fixture.latitude(),
                fixture.longitude(),
                fixture.status());
        allFixtures.add(item);
      }
    }

    return allFixtures;
  }

  private List<FootballCalendarItem> findFootballFixFixtures() {
    var allFixtures = new ArrayList<FootballCalendarItem>();
    var teams = teamsFactory.findFootballFixTeams();

    for (var team : teams) {
      // fetch fixtures from Football Fix
      var fixtures =
          footballFixClient.findFixtures(
              team.venueId(), team.leagueId(), team.seasonId(), team.divisionId());

      // filter for fixtures involving this team
      var teamFixtures =
          fixtures.stream()
              .filter(
                  fixture ->
                      fixture.homeTeamName().toLowerCase().contains(team.nameMatcher())
                          || fixture.awayTeamName().toLowerCase().contains(team.nameMatcher()))
              .toList();

      // map to dynamodb item
      for (var fixture : teamFixtures) {
        var item =
            FootballCalendarItem.create(
                team.id(),
                fixture.id(),
                fixture.homeTeamName(),
                fixture.awayTeamName(),
                fixture.timestamp(),
                fixture.venue(),
                team.address(),
                null,
                null,
                null);
        allFixtures.add(item);
      }
    }

    return allFixtures;
  }

  private List<FootballCalendarItem> findSubfootballFixtures() {
    var allFixtures = new ArrayList<FootballCalendarItem>();
    var teams = teamsFactory.findSubfootballTeams();

    for (var team : teams) {
      var fixtures = subfootballClient.findFixtures(team.teamId());

      for (var fixture : fixtures) {
        var item =
            FootballCalendarItem.create(
                team.id(),
                fixture.id(),
                fixture.homeTeamName(),
                fixture.awayTeamName(),
                fixture.timestamp(),
                fixture.venue(),
                team.address(),
                null,
                null,
                null);
        allFixtures.add(item);
      }
    }

    return allFixtures;
  }
}
