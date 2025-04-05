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
  void handleRequestShouldSaveFixturesToDb() {
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

    // Add test fixtures to the fake client
    var updatedFixture =
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

    var newFixture =
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

    var nonFlamingoFixture =
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

    fakeCometClient.addFixture(updatedFixture);
    fakeCometClient.addFixture(newFixture);
    fakeCometClient.addFixture(nonFlamingoFixture);

    // act
    updateFixturesHandler.handleRequest(event, null);

    // assert
    var items = footballCalendarTable.scan().items().stream().toList();

    // Should only have 2 items - the non-flamingo fixture should be filtered out
    assertThat(items).hasSize(2);

    // Verify all items have team="Flamingos"
    assertThat(items).allMatch(item -> "Flamingos".equals(item.getTeam()));

    // Verify PK format
    assertThat(items).allMatch(item -> item.getPk().equals("TEAM#Flamingos"));

    // Verify non-flamingo fixture is not saved
    assertThat(items).noneMatch(item -> item.getMatchId().equals(nonFlamingoFixture.id()));

    // Updated fixture - verify it was updated with new date
    var updatedItem =
        items.stream()
            .filter(item -> item.getMatchId().equals(existingFixtureId))
            .findFirst()
            .orElseThrow();
    assertThat(updatedItem.getHomeTeam()).isEqualTo(updatedFixture.homeTeamName());
    assertThat(updatedItem.getAwayTeam()).isEqualTo(updatedFixture.awayTeamName());
    assertThat(updatedItem.getTimestamp())
        .isEqualTo(updatedFixture.timestamp())
        .isNotEqualTo(existingFixture.getTimestamp());
    assertThat(updatedItem.getVenue()).isEqualTo(updatedFixture.venue());
    assertThat(updatedItem.getAddress()).isEqualTo(updatedFixture.address());
    assertThat(updatedItem.getLatitude()).isCloseTo(updatedFixture.latitude(), within(0.00001));
    assertThat(updatedItem.getLongitude()).isCloseTo(updatedFixture.longitude(), within(0.00001));
    assertThat(updatedItem.getStatus()).isEqualTo(updatedFixture.status());
    assertThat(updatedItem.getSk()).isEqualTo("MATCH#" + existingFixtureId);

    // New fixture
    var newItem =
        items.stream()
            .filter(item -> item.getMatchId().equals(newFixture.id()))
            .findFirst()
            .orElseThrow();
    assertThat(newItem.getHomeTeam()).isEqualTo(newFixture.homeTeamName());
    assertThat(newItem.getAwayTeam()).isEqualTo(newFixture.awayTeamName());
    assertThat(newItem.getTimestamp()).isEqualTo(newFixture.timestamp());
    assertThat(newItem.getVenue()).isEqualTo(newFixture.venue());
    assertThat(newItem.getAddress()).isEqualTo(newFixture.address());
    assertThat(newItem.getLatitude()).isCloseTo(newFixture.latitude(), within(0.00001));
    assertThat(newItem.getLongitude()).isCloseTo(newFixture.longitude(), within(0.00001));
    assertThat(newItem.getStatus()).isEqualTo(newFixture.status());
    assertThat(newItem.getSk()).isEqualTo("MATCH#" + newFixture.id());
  }
}
