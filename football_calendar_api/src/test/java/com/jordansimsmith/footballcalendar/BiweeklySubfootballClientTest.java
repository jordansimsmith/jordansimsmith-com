package com.jordansimsmith.footballcalendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

public class BiweeklySubfootballClientTest {
  @Mock HttpClient mockHttpClient;

  private BiweeklySubfootballClient client;

  private AutoCloseable openMocks;

  @BeforeEach
  void setUp() {
    openMocks = openMocks(this);
    client = new BiweeklySubfootballClient(mockHttpClient);
  }

  @AfterEach
  void tearDown() throws Exception {
    openMocks.close();
  }

  @Test
  void findFixturesShouldExtractAllFieldsCorrectly() throws Exception {
    // arrange
    var ical =
        """
        BEGIN:VCALENDAR
        PRODID:-//github.com/rianjs/ical.net//NONSGML ical.net 2.2//EN
        VERSION:2.0
        X-WR-CALNAME:Man I Love Football 2025/26 Summer Fixtures
        BEGIN:VEVENT
        DESCRIPTION:Field: Black\\nRound: 1\\nMan I Love Football and Swede as Bro FC
        DTEND:20251028T053000Z
        DTSTAMP:20251103T083105Z
        DTSTART:20251028T045000Z
        LOCATION:Auckland Domain\\, Auckland
        SEQUENCE:0
        SUMMARY:Round 1 - Man I Love Football vs Swede as Bro FC
        UID:8c19b36f-0b5d-41f9-aa9c-2779b6fff277
        END:VEVENT
        BEGIN:VEVENT
        DESCRIPTION:Field: Black\\nRound: 2\\nMan I Love Football and Ben's Broncos
        DTEND:20251104T061000Z
        DTSTAMP:20251103T083105Z
        DTSTART:20251104T053000Z
        LOCATION:Auckland Domain\\, Auckland
        SEQUENCE:0
        SUMMARY:Round 2 - Man I Love Football vs Ben's Broncos
        UID:23fd8573-eefc-404b-bc36-692a29d11f1f
        END:VEVENT
        BEGIN:VEVENT
        DESCRIPTION:Field: Black\\nRound: 3\\nMan I Love Football and Multiple Scorgasms
        DTEND:20251111T070000Z
        DTSTAMP:20251103T083105Z
        DTSTART:20251111T062000Z
        LOCATION:Auckland Domain\\, Auckland
        SEQUENCE:0
        SUMMARY:Round 3 - Man I Love Football vs Multiple Scorgasms
        UID:0e21a723-0840-4a78-ae6f-5cf9d0a14f92
        END:VEVENT
        END:VCALENDAR
        """;

    var mockResponse = createMockResponse(200, ical);
    when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockResponse);

    // act
    var fixtures = client.findFixtures("4326");

    // assert
    assertThat(fixtures).hasSize(3);

    var fixture1 =
        fixtures.stream()
            .filter(f -> f.id().equals("8c19b36f-0b5d-41f9-aa9c-2779b6fff277"))
            .findFirst()
            .orElseThrow();
    assertThat(fixture1.homeTeamName()).isEqualTo("Man I Love Football");
    assertThat(fixture1.awayTeamName()).isEqualTo("Swede as Bro FC");
    assertThat(fixture1.venue()).isEqualTo("Auckland Domain, Auckland");

    var expectedInstant1 =
        ZonedDateTime.of(2025, 10, 28, 4, 50, 0, 0, ZoneId.of("Pacific/Auckland")).toInstant();
    assertThat(fixture1.timestamp()).isEqualTo(expectedInstant1);

    var fixture2 =
        fixtures.stream()
            .filter(f -> f.id().equals("23fd8573-eefc-404b-bc36-692a29d11f1f"))
            .findFirst()
            .orElseThrow();
    assertThat(fixture2.homeTeamName()).isEqualTo("Man I Love Football");
    assertThat(fixture2.awayTeamName()).isEqualTo("Ben's Broncos");
    assertThat(fixture2.venue()).isEqualTo("Auckland Domain, Auckland");

    var expectedInstant2 =
        ZonedDateTime.of(2025, 11, 4, 5, 30, 0, 0, ZoneId.of("Pacific/Auckland")).toInstant();
    assertThat(fixture2.timestamp()).isEqualTo(expectedInstant2);

    var fixture3 =
        fixtures.stream()
            .filter(f -> f.id().equals("0e21a723-0840-4a78-ae6f-5cf9d0a14f92"))
            .findFirst()
            .orElseThrow();
    assertThat(fixture3.homeTeamName()).isEqualTo("Man I Love Football");
    assertThat(fixture3.awayTeamName()).isEqualTo("Multiple Scorgasms");
    assertThat(fixture3.venue()).isEqualTo("Auckland Domain, Auckland");

    var expectedInstant3 =
        ZonedDateTime.of(2025, 11, 11, 6, 20, 0, 0, ZoneId.of("Pacific/Auckland")).toInstant();
    assertThat(fixture3.timestamp()).isEqualTo(expectedInstant3);
  }

  @Test
  void findFixturesShouldHandleEscapedCharactersInLocation() throws Exception {
    // arrange
    var ical =
        """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        DTSTART:20251028T045000Z
        LOCATION:Auckland Domain\\, Auckland
        SUMMARY:Round 1 - Team A vs Team B
        UID:test-uid-1
        END:VEVENT
        END:VCALENDAR
        """;

    var mockResponse = createMockResponse(200, ical);
    when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockResponse);

    // act
    var fixtures = client.findFixtures("4326");

    // assert
    assertThat(fixtures).hasSize(1);
    assertThat(fixtures.get(0).venue()).isEqualTo("Auckland Domain, Auckland");
  }

  @Test
  void findFixturesShouldParseTeamsFromSummaryWithRoundPrefix() throws Exception {
    // arrange
    var ical =
        """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        DTSTART:20251028T045000Z
        LOCATION:Test Venue
        SUMMARY:Round 5 - Home Team Name vs Away Team Name
        UID:test-uid-2
        END:VEVENT
        END:VCALENDAR
        """;

    var mockResponse = createMockResponse(200, ical);
    when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockResponse);

    // act
    var fixtures = client.findFixtures("4326");

    // assert
    assertThat(fixtures).hasSize(1);
    assertThat(fixtures.get(0).homeTeamName()).isEqualTo("Home Team Name");
    assertThat(fixtures.get(0).awayTeamName()).isEqualTo("Away Team Name");
  }

  @Test
  void findFixturesShouldHandleMultipleEventsOnDifferentDates() throws Exception {
    // arrange
    var ical =
        """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        DTSTART:20251028T045000Z
        LOCATION:Venue 1
        SUMMARY:Round 1 - Team A vs Team B
        UID:uid-1
        END:VEVENT
        BEGIN:VEVENT
        DTSTART:20251104T053000Z
        LOCATION:Venue 2
        SUMMARY:Round 2 - Team C vs Team D
        UID:uid-2
        END:VEVENT
        BEGIN:VEVENT
        DTSTART:20251111T062000Z
        LOCATION:Venue 3
        SUMMARY:Round 3 - Team E vs Team F
        UID:uid-3
        END:VEVENT
        END:VCALENDAR
        """;

    var mockResponse = createMockResponse(200, ical);
    when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockResponse);

    // act
    var fixtures = client.findFixtures("4326");

    // assert
    assertThat(fixtures).hasSize(3);

    var oct28Fixtures =
        fixtures.stream()
            .filter(f -> f.timestamp().atZone(ZoneId.of("Pacific/Auckland")).getDayOfMonth() == 28)
            .toList();
    assertThat(oct28Fixtures).hasSize(1);

    var nov4Fixtures =
        fixtures.stream()
            .filter(
                f ->
                    f.timestamp().atZone(ZoneId.of("Pacific/Auckland")).getDayOfMonth() == 4
                        && f.timestamp().atZone(ZoneId.of("Pacific/Auckland")).getMonthValue()
                            == 11)
            .toList();
    assertThat(nov4Fixtures).hasSize(1);

    var nov11Fixtures =
        fixtures.stream()
            .filter(
                f ->
                    f.timestamp().atZone(ZoneId.of("Pacific/Auckland")).getDayOfMonth() == 11
                        && f.timestamp().atZone(ZoneId.of("Pacific/Auckland")).getMonthValue()
                            == 11)
            .toList();
    assertThat(nov11Fixtures).hasSize(1);
  }

  @Test
  void findFixturesShouldSkipEventsWithMissingFields() throws Exception {
    // arrange
    var ical =
        """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        DTSTART:20251028T045000Z
        LOCATION:Venue
        SUMMARY:Round 1 - Team A vs Team B
        UID:valid-uid
        END:VEVENT
        BEGIN:VEVENT
        DTSTART:20251028T045000Z
        LOCATION:Venue
        SUMMARY:Round 2 - Team C vs Team D
        END:VEVENT
        BEGIN:VEVENT
        DTSTART:20251028T045000Z
        SUMMARY:Round 3 - Team E vs Team F
        UID:no-location-uid
        END:VEVENT
        BEGIN:VEVENT
        LOCATION:Venue
        SUMMARY:Round 4 - Team G vs Team H
        UID:no-dtstart-uid
        END:VEVENT
        END:VCALENDAR
        """;

    var mockResponse = createMockResponse(200, ical);
    when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockResponse);

    // act
    var fixtures = client.findFixtures("4326");

    // assert
    assertThat(fixtures).hasSize(1);
    assertThat(fixtures.get(0).id()).isEqualTo("valid-uid");
  }

  @SuppressWarnings("unchecked")
  private <T> HttpResponse<T> createMockResponse(int statusCode, T body) {
    HttpResponse<T> response = (HttpResponse<T>) org.mockito.Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(statusCode);
    when(response.body()).thenReturn(body);
    return response;
  }
}
