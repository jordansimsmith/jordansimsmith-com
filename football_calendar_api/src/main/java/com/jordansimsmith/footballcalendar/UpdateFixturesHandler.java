package com.jordansimsmith.footballcalendar;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.annotations.VisibleForTesting;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

public class UpdateFixturesHandler implements RequestHandler<ScheduledEvent, Void> {
  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateFixturesHandler.class);

  private final DynamoDbTable<FootballCalendarItem> footballCalendarTable;
  private final CometClient cometClient;
  private final FootballFixClient footballFixClient;
  private final SubfootballClient subfootballClient;
  private final TeamsFactory teamsFactory;

  public UpdateFixturesHandler() {
    this(FootballCalendarFactory.create());
  }

  @VisibleForTesting
  UpdateFixturesHandler(FootballCalendarFactory factory) {
    this.footballCalendarTable = factory.footballCalendarTable();
    this.cometClient = factory.cometClient();
    this.footballFixClient = factory.footballFixClient();
    this.subfootballClient = factory.subfootballClient();
    this.teamsFactory = factory.teamsFactory();
  }

  @Override
  public Void handleRequest(ScheduledEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("Error processing football fixtures update", e);
      throw new RuntimeException(e);
    }
  }

  private Void doHandleRequest(ScheduledEvent event, Context context) {
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

      // create a set of match IDs from the API response to use for comparison
      var newFixtureIds =
          fixtures.stream().map(FootballCalendarItem::getMatchId).collect(Collectors.toSet());

      // delete fixtures that no longer exist in the API response
      for (var existingFixture : existingFixtures) {
        if (!newFixtureIds.contains(existingFixture.getMatchId())) {
          footballCalendarTable.deleteItem(existingFixture);
        }
      }

      // update or add current fixtures
      for (var fixture : fixtures) {
        footballCalendarTable.putItem(fixture);
      }
    }

    return null;
  }

  private List<FootballCalendarItem> findNorthernRegionalFootballFixtures() {
    var allFixtures = new ArrayList<FootballCalendarItem>();
    var teams = teamsFactory.findNorthernRegionalFootballTeams();

    // process each team separately
    for (var team : teams) {

      // fetch fixtures from all competitions for this team
      var seasonYear = Integer.parseInt(team.seasonId());
      var from = ZonedDateTime.of(seasonYear, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault()).toInstant();
      var to =
          ZonedDateTime.of(seasonYear, 12, 31, 23, 59, 59, 0, ZoneId.systemDefault()).toInstant();
      var fixtures =
          cometClient.getFixtures(
              team.seasonId(), team.competitionId(), List.of(team.clubId()), from, to);

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
          footballFixClient.getFixtures(
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
      var fixtures = subfootballClient.getFixtures(team.teamId());

      for (var fixture : fixtures) {
        var item =
            FootballCalendarItem.create(
                team.id(),
                fixture.id(),
                fixture.homeTeamName(),
                fixture.awayTeamName(),
                fixture.timestamp(),
                fixture.venue(),
                null,
                null,
                null,
                null);
        allFixtures.add(item);
      }
    }

    return allFixtures;
  }
}
