package com.jordansimsmith.footballcalendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class UpdateFixturesHandlerIntegrationTest {
  private static final String NRF_MENS_DIV_6_CENTRAL_EAST = "2716594877";
  private static final String NRF_MENS_COMMUNITY_CUP = "2714644497";

  private FakeClock fakeClock;
  private FakeCometClient fakeCometClient;
  private FakeFootballFixClient fakeFootballFixClient;
  private FakeTeamsFactory fakeTeamsFactory;
  private DynamoDbTable<FootballCalendarItem> footballCalendarTable;

  private UpdateFixturesHandler updateFixturesHandler;

  @Container DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeEach
  void setUp() {
    var factory = FootballCalendarTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeCometClient = factory.fakeCometClient();
    fakeFootballFixClient = factory.fakeFootballFixClient();
    fakeTeamsFactory = factory.fakeTeamsFactory();
    footballCalendarTable = factory.footballCalendarTable();

    DynamoDbUtils.createTable(factory.dynamoDbClient(), footballCalendarTable);

    updateFixturesHandler = new UpdateFixturesHandler(factory);
  }

  @Test
  void handleRequestShouldSaveFixturesFromMultipleCompetitionsToDb() {
    // arrange
    var testTime = Instant.parse("2023-05-01T10:00:00Z");
    fakeClock.setTime(testTime);
    var event = new ScheduledEvent();

    fakeTeamsFactory.addTeam(
        new TeamsFactory.NorthernRegionalFootballTeam(
            "Flamingos", "flamingo", "44838", NRF_MENS_DIV_6_CENTRAL_EAST, "2025"));
    fakeTeamsFactory.addTeam(
        new TeamsFactory.NorthernRegionalFootballTeam(
            "Flamingos", "flamingo", "44838", NRF_MENS_COMMUNITY_CUP, "2025"));

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

    fakeCometClient.addFixture(NRF_MENS_DIV_6_CENTRAL_EAST, leagueFixture);
    fakeCometClient.addFixture(NRF_MENS_DIV_6_CENTRAL_EAST, nonFlamingoLeagueFixture);

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

    fakeCometClient.addFixture(NRF_MENS_COMMUNITY_CUP, cupFixture);
    fakeCometClient.addFixture(NRF_MENS_COMMUNITY_CUP, nonFlamingoCupFixture);

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

  @Test
  void handleRequestShouldDeleteFixturesThatNoLongerExistInApi() {
    // arrange
    var testTime = Instant.parse("2023-05-01T10:00:00Z");
    fakeClock.setTime(testTime);
    var event = new ScheduledEvent();

    fakeTeamsFactory.addTeam(
        new TeamsFactory.NorthernRegionalFootballTeam(
            "Flamingos", "flamingo", "44838", NRF_MENS_DIV_6_CENTRAL_EAST, "2025"));

    // Create three pre-existing fixtures in DB
    var existingFixture1 =
        FootballCalendarItem.create(
            "Flamingos",
            "fixture1",
            "Eastern Suburbs AFC",
            "Ellerslie AFC Flamingos M",
            Instant.parse("2023-05-07T14:00:00Z"),
            "Madills Farm",
            "20 Melanesia Road, Kohimarama, Auckland",
            -36.8485,
            174.8582,
            "Scheduled");

    var existingFixture2 =
        FootballCalendarItem.create(
            "Flamingos",
            "fixture2",
            "Ellerslie AFC Flamingos M",
            "Auckland United",
            Instant.parse("2023-05-14T14:00:00Z"),
            "Ellerslie Domain",
            "10 Main Highway, Ellerslie, Auckland",
            -36.8995,
            174.8140,
            "Scheduled");

    var existingFixture3 =
        FootballCalendarItem.create(
            "Flamingos",
            "fixture3",
            "Ellerslie AFC Flamingos M",
            "Western Springs",
            Instant.parse("2023-05-21T14:00:00Z"),
            "Seddon Fields",
            "180 Meola Road, Point Chevalier, Auckland",
            -36.8605,
            174.7280,
            "Scheduled");

    footballCalendarTable.putItem(existingFixture1);
    footballCalendarTable.putItem(existingFixture2);
    footballCalendarTable.putItem(existingFixture3);

    // Only add fixtures 1 and 2 to the API response (fixture3 is now canceled/removed)
    var apiFixture1 =
        new CometClient.FootballFixture(
            "fixture1",
            "Eastern Suburbs AFC",
            "Ellerslie AFC Flamingos M",
            Instant.parse("2023-05-07T14:00:00Z"),
            "Madills Farm",
            "20 Melanesia Road, Kohimarama, Auckland",
            -36.8485,
            174.8582,
            "Scheduled");

    var apiFixture2 =
        new CometClient.FootballFixture(
            "fixture2",
            "Ellerslie AFC Flamingos M",
            "Auckland United",
            Instant.parse("2023-05-14T14:00:00Z"),
            "Ellerslie Domain",
            "10 Main Highway, Ellerslie, Auckland",
            -36.8995,
            174.8140,
            "Scheduled");

    fakeCometClient.addFixture(NRF_MENS_DIV_6_CENTRAL_EAST, apiFixture1);
    fakeCometClient.addFixture(NRF_MENS_DIV_6_CENTRAL_EAST, apiFixture2);

    // act
    updateFixturesHandler.handleRequest(event, null);

    // assert
    var items = footballCalendarTable.scan().items().stream().toList();

    // Should only have 2 items - the fixtures returned by the API
    assertThat(items).hasSize(2);

    // Verify the correct fixtures exist
    assertThat(items).anyMatch(item -> item.getMatchId().equals("fixture1"));
    assertThat(items).anyMatch(item -> item.getMatchId().equals("fixture2"));

    // Verify fixture3 was deleted
    assertThat(items).noneMatch(item -> item.getMatchId().equals("fixture3"));
  }

  @Test
  void handleRequestShouldSaveFootballFixFixturesToDb() {
    // arrange
    var testTime = Instant.parse("2025-10-23T10:00:00Z");
    fakeClock.setTime(testTime);
    var event = new ScheduledEvent();

    fakeTeamsFactory.addTeam(
        new TeamsFactory.FootballFixTeam(
            "Flamingos Sevens", "flamingoes", "13", "131", "89", "6030"));

    // add Football Fix fixtures
    var flamingoesFixture =
        new FootballFixClient.FootballFixture(
            "148617",
            "Lad FC",
            "Flamingoes",
            Instant.parse("2025-10-23T08:40:00Z"),
            "Field 2",
            "3/25 Normanby Road, Mount Eden, Auckland 1024");

    var nonFlamingoesFixture =
        new FootballFixClient.FootballFixture(
            "148618",
            "Jesus and the Shepherds",
            "G-Raves RC",
            Instant.parse("2025-10-23T07:20:00Z"),
            "Field 1",
            "3/25 Normanby Road, Mount Eden, Auckland 1024");

    fakeFootballFixClient.addFixture("6030", flamingoesFixture);
    fakeFootballFixClient.addFixture("6030", nonFlamingoesFixture);

    // act
    updateFixturesHandler.handleRequest(event, null);

    // assert
    var items = footballCalendarTable.scan().items().stream().toList();

    // should only have 1 item - the Flamingoes fixture
    assertThat(items).hasSize(1);

    // verify team
    assertThat(items).allMatch(item -> "Flamingos Sevens".equals(item.getTeam()));
    assertThat(items).allMatch(item -> item.getPk().equals("TEAM#Flamingos Sevens"));

    // verify non-flamingoes fixture is not saved
    assertThat(items).noneMatch(item -> item.getMatchId().equals(nonFlamingoesFixture.id()));

    // verify Flamingoes fixture
    var item = items.get(0);
    assertThat(item.getMatchId()).isEqualTo(flamingoesFixture.id());
    assertThat(item.getHomeTeam()).isEqualTo(flamingoesFixture.homeTeamName());
    assertThat(item.getAwayTeam()).isEqualTo(flamingoesFixture.awayTeamName());
    assertThat(item.getTimestamp()).isEqualTo(flamingoesFixture.timestamp());
    assertThat(item.getVenue()).isEqualTo(flamingoesFixture.venue());
    assertThat(item.getAddress()).isEqualTo(flamingoesFixture.address());
    assertThat(item.getLatitude()).isNull();
    assertThat(item.getLongitude()).isNull();
    assertThat(item.getStatus()).isNull();
    assertThat(item.getSk()).isEqualTo("MATCH#148617");
  }

  @Test
  void handleRequestShouldSaveFixturesFromBothNrfAndFootballFix() {
    // arrange
    var testTime = Instant.parse("2025-10-23T10:00:00Z");
    fakeClock.setTime(testTime);
    var event = new ScheduledEvent();

    // add NRF team
    fakeTeamsFactory.addTeam(
        new TeamsFactory.NorthernRegionalFootballTeam(
            "Flamingos", "flamingo", "44838", NRF_MENS_DIV_6_CENTRAL_EAST, "2025"));

    // add Football Fix team
    fakeTeamsFactory.addTeam(
        new TeamsFactory.FootballFixTeam(
            "Flamingos Sevens", "flamingoes", "13", "131", "89", "6030"));

    // add NRF fixture
    var nrfFixture =
        new CometClient.FootballFixture(
            "123456",
            "Eastern Suburbs AFC",
            "Ellerslie AFC Flamingos M",
            Instant.parse("2025-10-25T14:00:00Z"),
            "Madills Farm",
            "20 Melanesia Road, Kohimarama, Auckland",
            -36.8485,
            174.8582,
            "Scheduled");

    fakeCometClient.addFixture(NRF_MENS_DIV_6_CENTRAL_EAST, nrfFixture);

    // add Football Fix fixture
    var footballFixFixture =
        new FootballFixClient.FootballFixture(
            "148617",
            "Flamingoes",
            "Lad FC",
            Instant.parse("2025-10-30T07:20:00Z"),
            "Field 2",
            "3/25 Normanby Road, Mount Eden, Auckland 1024");

    fakeFootballFixClient.addFixture("6030", footballFixFixture);

    // act
    updateFixturesHandler.handleRequest(event, null);

    // assert
    var items = footballCalendarTable.scan().items().stream().toList();

    // should have 2 items - one from each source
    assertThat(items).hasSize(2);

    // verify NRF fixture
    var nrfItem =
        items.stream()
            .filter(item -> item.getMatchId().equals(nrfFixture.id()))
            .findFirst()
            .orElseThrow();
    assertThat(nrfItem.getTeam()).isEqualTo("Flamingos");
    assertThat(nrfItem.getHomeTeam()).isEqualTo(nrfFixture.homeTeamName());
    assertThat(nrfItem.getAwayTeam()).isEqualTo(nrfFixture.awayTeamName());
    assertThat(nrfItem.getTimestamp()).isEqualTo(nrfFixture.timestamp());
    assertThat(nrfItem.getVenue()).isEqualTo(nrfFixture.venue());
    assertThat(nrfItem.getLatitude()).isCloseTo(nrfFixture.latitude(), within(0.00001));
    assertThat(nrfItem.getLongitude()).isCloseTo(nrfFixture.longitude(), within(0.00001));
    assertThat(nrfItem.getStatus()).isEqualTo(nrfFixture.status());

    // verify Football Fix fixture
    var footballFixItem =
        items.stream()
            .filter(item -> item.getMatchId().equals(footballFixFixture.id()))
            .findFirst()
            .orElseThrow();
    assertThat(footballFixItem.getTeam()).isEqualTo("Flamingos Sevens");
    assertThat(footballFixItem.getHomeTeam()).isEqualTo(footballFixFixture.homeTeamName());
    assertThat(footballFixItem.getAwayTeam()).isEqualTo(footballFixFixture.awayTeamName());
    assertThat(footballFixItem.getTimestamp()).isEqualTo(footballFixFixture.timestamp());
    assertThat(footballFixItem.getVenue()).isEqualTo(footballFixFixture.venue());
    assertThat(footballFixItem.getLatitude()).isNull();
    assertThat(footballFixItem.getLongitude()).isNull();
    assertThat(footballFixItem.getStatus()).isNull();
  }
}
