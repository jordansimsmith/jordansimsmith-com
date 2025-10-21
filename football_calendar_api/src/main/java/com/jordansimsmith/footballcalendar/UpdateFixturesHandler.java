package com.jordansimsmith.footballcalendar;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.jordansimsmith.time.Clock;
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
  private final Clock clock;
  private final TeamsFactory teamsFactory;

  public UpdateFixturesHandler() {
    this(FootballCalendarFactory.create());
  }

  @VisibleForTesting
  UpdateFixturesHandler(FootballCalendarFactory factory) {
    this.footballCalendarTable = factory.footballCalendarTable();
    this.cometClient = factory.cometClient();
    this.clock = factory.clock();
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
    var nrfTeams = teamsFactory.findNorthernRegionalFootballTeams();

    // group team configurations by id
    var nrfTeamsByTeamId =
        nrfTeams.stream()
            .collect(Collectors.groupingBy(TeamsFactory.NorthernRegionalFootballTeam::id));

    // process each team separately
    for (var entry : nrfTeamsByTeamId.entrySet()) {
      var teamId = entry.getKey();
      var nrfTeamsForTeamId = entry.getValue();

      var distinctNameMatchers =
          nrfTeamsForTeamId.stream()
              .map(TeamsFactory.NorthernRegionalFootballTeam::nameMatcher)
              .distinct()
              .toList();
      Preconditions.checkState(
          distinctNameMatchers.size() == 1,
          "All team configs for the same team must have the same nameMatcher");
      var nameMatcher = nrfTeamsForTeamId.get(0).nameMatcher();

      // fetch fixtures from all competitions for this team
      var allFixtures = new ArrayList<CometClient.FootballFixture>();
      for (var nrfTeam : nrfTeamsForTeamId) {
        var seasonId = nrfTeam.seasonId();
        var seasonYear = Integer.parseInt(seasonId);
        var from =
            ZonedDateTime.of(seasonYear, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault()).toInstant();
        var to =
            ZonedDateTime.of(seasonYear, 12, 31, 23, 59, 59, 0, ZoneId.systemDefault()).toInstant();

        var fixtures =
            cometClient.getFixtures(
                seasonId, nrfTeam.competitionId(), List.of(nrfTeam.clubId()), from, to);
        allFixtures.addAll(fixtures);
      }

      // filter for fixtures involving this team
      var teamFixtures =
          allFixtures.stream()
              .filter(
                  fixture ->
                      fixture.homeTeamName().toLowerCase().contains(nameMatcher)
                          || fixture.awayTeamName().toLowerCase().contains(nameMatcher))
              .toList();

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
      var currentFixtureIds =
          teamFixtures.stream().map(CometClient.FootballFixture::id).collect(Collectors.toSet());

      // delete fixtures that no longer exist in the API response
      for (var existingFixture : existingFixtures) {
        if (!currentFixtureIds.contains(existingFixture.getMatchId())) {
          footballCalendarTable.deleteItem(existingFixture);
        }
      }

      // update or add current fixtures
      for (var fixture : teamFixtures) {
        var item =
            FootballCalendarItem.create(
                teamId,
                fixture.id(),
                fixture.homeTeamName(),
                fixture.awayTeamName(),
                fixture.timestamp(),
                fixture.venue(),
                fixture.address(),
                fixture.latitude(),
                fixture.longitude(),
                fixture.status());

        footballCalendarTable.putItem(item);
      }
    }

    return null;
  }
}
