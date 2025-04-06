package com.jordansimsmith.footballcalendar;

import static com.jordansimsmith.footballcalendar.Teams.ELLERSLIE_FLAMINGOS;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

public class UpdateFixturesHandler implements RequestHandler<ScheduledEvent, Void> {
  private static final String NRF_MENS_DIV_6_CENTRAL_EAST = "2716594877";
  private static final String ELLERSLIE = "44838";

  private final DynamoDbTable<FootballCalendarItem> footballCalendarTable;
  private final CometClient cometClient;
  private final Clock clock;

  public UpdateFixturesHandler() {
    this(FootballCalendarFactory.create());
  }

  @VisibleForTesting
  UpdateFixturesHandler(FootballCalendarFactory factory) {
    this.footballCalendarTable = factory.footballCalendarTable();
    this.cometClient = factory.cometClient();
    this.clock = factory.clock();
  }

  @Override
  public Void handleRequest(ScheduledEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Void doHandleRequest(ScheduledEvent event, Context context) {
    // Get current year
    var now = clock.now();
    var currentYear = ZonedDateTime.ofInstant(now, ZoneId.systemDefault()).getYear();
    String seasonId = String.valueOf(currentYear);

    // Set date range: from start of year to end of year
    var from = ZonedDateTime.of(currentYear, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault()).toInstant();
    var to =
        ZonedDateTime.of(currentYear, 12, 31, 23, 59, 59, 0, ZoneId.systemDefault()).toInstant();

    // Get fixtures from the Comet API
    var fixtures =
        cometClient.getFixtures(
            seasonId, NRF_MENS_DIV_6_CENTRAL_EAST, List.of(ELLERSLIE), from, to);

    // Filter for fixtures involving Flamingos team
    var flamingoFixtures =
        fixtures.stream()
            .filter(
                fixture ->
                    fixture.homeTeamName().toLowerCase().contains("flamingo")
                        || fixture.awayTeamName().toLowerCase().contains("flamingo"))
            .toList();

    // Save each fixture to DynamoDB with Flamingos as the team
    for (var fixture : flamingoFixtures) {
      var item =
          FootballCalendarItem.create(
              ELLERSLIE_FLAMINGOS,
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

    return null;
  }
}
