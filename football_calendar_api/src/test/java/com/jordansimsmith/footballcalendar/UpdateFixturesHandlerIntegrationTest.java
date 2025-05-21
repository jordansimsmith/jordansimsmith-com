package com.jordansimsmith.footballcalendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.testcontainers.DynamoDbContainer;
import com.jordansimsmith.time.FakeClock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class UpdateFixturesHandlerIntegrationTest {
  private FakeClock fakeClock;
  private FakeCometClient fakeCometClient;
  private DynamoDbTable<FootballCalendarItem> footballCalendarTable;

  private UpdateFixturesHandler updateFixturesHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = FootballCalendarTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeCometClient = factory.fakeCometClient();
    footballCalendarTable = factory.footballCalendarTable();

    DynamoDbUtils.createTable(factory.dynamoDbClient(), footballCalendarTable);

    updateFixturesHandler = new UpdateFixturesHandler(factory);
  }

  @Test
  void handleRequestShouldSaveFixturesFromMultipleCompetitionsToDb() {
    // arrange
    var testTime = Instant.parse("2023-05-01T10:00:00Z");
    fakeClock.setTime(testTime.toEpochMilli());
    var event = new ScheduledEvent();

    // Create a pre-existing fixture with old date
    var existingFixtureId = "123456";
    var existingFixture =
        FootballCalendarItem.create(
            "Flamingos",
            existingFixtureId,
            "Eastern Suburbs AFC",
            "Ellerslie AFC Flamingos M",
            Instant.parse("2023-05-07T14:00:00Z"),
            "Madills Farm",
            "20 Melanesia Road, Kohimarama, Auckland",
            -36.8485,
            174.8582,
            "Scheduled");
    footballCalendarTable.putItem(existingFixture);

    // Add league fixtures to the fake client
    var leagueFixture =
        new CometClient.FootballFixture(
            existingFixtureId,
            "Eastern Suburbs AFC",
            "Ellerslie AFC Flamingos M",
            Instant.parse("2023-05-08T14:00:00Z"), // Changed date
            "Madills Farm",
            "20 Melanesia Road, Kohimarama, Auckland",
            -36.8485,
            174.8582,
            "Scheduled");

    var nonFlamingoLeagueFixture =
        new CometClient.FootballFixture(
            "345678",
            "Team A",
            "Team B",
            Instant.parse("2023-05-15T16:00:00Z"),
            "Some Stadium",
            "123 Some Street, Auckland",
            -36.8700,
            174.7300,
            "Scheduled");

    fakeCometClient.addFixture(UpdateFixturesHandler.NRF_MENS_DIV_6_CENTRAL_EAST, leagueFixture);
    fakeCometClient.addFixture(
        UpdateFixturesHandler.NRF_MENS_DIV_6_CENTRAL_EAST, nonFlamingoLeagueFixture);

    // Add cup fixtures to the fake client
    var cupFixture =
        new CometClient.FootballFixture(
            "789012",
            "Ellerslie AFC Flamingos M",
            "Birkenhead United",
            Instant.parse("2023-05-14T15:30:00Z"),
            "Western Springs Stadium",
            "731 Great North Road, Western Springs, Auckland",
            -36.8653,
            174.7232,
            "Scheduled");

    var nonFlamingoCupFixture =
        new CometClient.FootballFixture(
            "901234",
            "Team C",
            "Team D",
            Instant.parse("2023-05-16T17:00:00Z"),
            "Another Stadium",
            "456 Another Street, Auckland",
            -36.8800,
            174.7400,
            "Scheduled");

    fakeCometClient.addFixture(UpdateFixturesHandler.NRF_MENS_COMMUNITY_CUP, cupFixture);
    fakeCometClient.addFixture(UpdateFixturesHandler.NRF_MENS_COMMUNITY_CUP, nonFlamingoCupFixture);

    // act
    updateFixturesHandler.handleRequest(event, null);

    // assert
    var items = footballCalendarTable.scan().items().stream().toList();

    // Should only have 2 items - the Flamingo fixtures from both competitions
    assertThat(items).hasSize(2);

    // Verify all items have team="Flamingos"
    assertThat(items).allMatch(item -> "Flamingos".equals(item.getTeam()));

    // Verify PK format
    assertThat(items).allMatch(item -> item.getPk().equals("TEAM#Flamingos"));

    // Verify non-flamingo fixtures are not saved
    assertThat(items).noneMatch(item -> item.getMatchId().equals(nonFlamingoLeagueFixture.id()));
    assertThat(items).noneMatch(item -> item.getMatchId().equals(nonFlamingoCupFixture.id()));

    // League fixture - verify it was updated with new date
    var updatedItem =
        items.stream()
            .filter(item -> item.getMatchId().equals(existingFixtureId))
            .findFirst()
            .orElseThrow();
    assertThat(updatedItem.getHomeTeam()).isEqualTo(leagueFixture.homeTeamName());
    assertThat(updatedItem.getAwayTeam()).isEqualTo(leagueFixture.awayTeamName());
    assertThat(updatedItem.getTimestamp())
        .isEqualTo(leagueFixture.timestamp())
        .isNotEqualTo(existingFixture.getTimestamp());
    assertThat(updatedItem.getVenue()).isEqualTo(leagueFixture.venue());
    assertThat(updatedItem.getAddress()).isEqualTo(leagueFixture.address());
    assertThat(updatedItem.getLatitude()).isCloseTo(leagueFixture.latitude(), within(0.00001));
    assertThat(updatedItem.getLongitude()).isCloseTo(leagueFixture.longitude(), within(0.00001));
    assertThat(updatedItem.getStatus()).isEqualTo(leagueFixture.status());
    assertThat(updatedItem.getSk()).isEqualTo("MATCH#" + existingFixtureId);

    // Cup fixture
    var cupItem =
        items.stream()
            .filter(item -> item.getMatchId().equals(cupFixture.id()))
            .findFirst()
            .orElseThrow();
    assertThat(cupItem.getHomeTeam()).isEqualTo(cupFixture.homeTeamName());
    assertThat(cupItem.getAwayTeam()).isEqualTo(cupFixture.awayTeamName());
    assertThat(cupItem.getTimestamp()).isEqualTo(cupFixture.timestamp());
    assertThat(cupItem.getVenue()).isEqualTo(cupFixture.venue());
    assertThat(cupItem.getAddress()).isEqualTo(cupFixture.address());
    assertThat(cupItem.getLatitude()).isCloseTo(cupFixture.latitude(), within(0.00001));
    assertThat(cupItem.getLongitude()).isCloseTo(cupFixture.longitude(), within(0.00001));
    assertThat(cupItem.getStatus()).isEqualTo(cupFixture.status());
    assertThat(cupItem.getSk()).isEqualTo("MATCH#" + cupFixture.id());
  }
}
