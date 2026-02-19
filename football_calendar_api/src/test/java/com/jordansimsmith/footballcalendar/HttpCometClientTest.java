package com.jordansimsmith.footballcalendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

public class HttpCometClientTest {
  @Mock HttpClient mockHttpClient;

  private HttpCometClient client;

  private AutoCloseable openMocks;

  @BeforeEach
  void setUp() {
    openMocks = openMocks(this);
    var objectMapper = new ObjectMapper();
    client = new HttpCometClient(mockHttpClient, objectMapper);
  }

  @AfterEach
  void tearDown() throws Exception {
    openMocks.close();
  }

  @Test
  void findFixturesShouldReturnParsedFixturesWhenSuccessful() throws Exception {
    // arrange
    var mockResponse = createMockResponse(200, readFixturesResponse());
    when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockResponse);

    var seasonId = "2025";
    var competitionId = "2716594877";
    var organisationIds = List.of("44838");
    var from = Instant.parse("2025-04-05T00:00:00Z");
    var to = Instant.parse("2025-04-11T00:00:00Z");

    // act
    var fixtures = client.findFixtures(seasonId, competitionId, organisationIds, from, to);

    // assert
    assertThat(fixtures).isNotNull();
    assertThat(fixtures).hasSize(1);
    var fixture = fixtures.get(0);
    assertThat(fixture.id()).isEqualTo("2716942185");
    assertThat(fixture.homeTeamName()).isEqualTo("Bucklands Beach Bucks M5");
    assertThat(fixture.awayTeamName()).isEqualTo("Ellerslie AFC Flamingoes M");
    // Convert from Auckland time to UTC for comparison
    var expectedLocalDateTime = LocalDateTime.of(2025, 4, 5, 15, 0, 0);
    var expectedInstant = expectedLocalDateTime.atZone(ZoneId.of("Pacific/Auckland")).toInstant();
    assertThat(fixture.timestamp()).isEqualTo(expectedInstant);
    assertThat(fixture.venue()).isEqualTo("Lloyd Elsmore Park 2");
    assertThat(fixture.address()).isEqualTo("2 Bells Avenue");
    assertThat(fixture.latitude()).isEqualTo(-36.9053315);
    assertThat(fixture.longitude()).isEqualTo(174.8997797);
    assertThat(fixture.status()).isEqualTo("POSTPONED");

    // verify request body
    var requestCaptor = forClass(HttpRequest.class);
    verify(mockHttpClient).send(requestCaptor.capture(), any());
    var actualRequest = requestCaptor.getValue();
    assertThat(actualRequest.uri().toString())
        .isEqualTo("https://www.nrf.org.nz/api/1.0/competition/cometwidget/filteredfixtures");
    assertThat(actualRequest.headers().firstValue("Content-Type"))
        .isPresent()
        .get()
        .isEqualTo("application/json");
  }

  @Test
  void findFixturesShouldThrowExceptionWhenApiReturnsErrorStatus() throws Exception {
    // arrange
    var mockResponse = createMockResponse(500, "Server error");
    when(mockHttpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockResponse);

    var seasonId = "2025";
    var competitionId = "2716594877";
    var organisationIds = List.of("44838");
    var from = Instant.parse("2025-04-05T00:00:00Z");
    var to = Instant.parse("2025-04-11T00:00:00Z");

    // act & assert
    assertThrows(
        RuntimeException.class,
        () -> client.findFixtures(seasonId, competitionId, organisationIds, from, to),
        "API request failed with status: 500");
  }

  @SuppressWarnings("unchecked")
  private <T> HttpResponse<T> createMockResponse(int statusCode, T body) {
    HttpResponse<T> response = (HttpResponse<T>) Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(statusCode);
    when(response.body()).thenReturn(body);
    return response;
  }

  private String readFixturesResponse() {
    return """
    {
      "fixtures": [
        {
          "Id": "2716942185",
          "HomeOrgLogo": "//prodcdn.sporty.co.nz/cometcache/comet/logo/285712",
          "AwayOrgLogo": "//prodcdn.sporty.co.nz/cometcache/comet/logo/289232",
          "GradeId": "Grade",
          "GradeName": "Grade",
          "HomeTeamNameAbbr": "Bucklands Beach Bucks M5",
          "AwayTeamNameAbbr": "Ellerslie AFC Flamingoes M",
          "CompetitionId": null,
          "Round": "Round",
          "RoundName": "Round",
          "Date": "2025-04-05T15:00:00",
          "VenueId": "47651",
          "VenueName": "Lloyd Elsmore Park 2",
          "GLN": "9429302884032",
          "HomeScore": "",
          "AwayScore": "",
          "SectionId": 0,
          "SectionName": null,
          "PublicNotes": null,
          "CssName": null,
          "MatchSummary": null,
          "MatchDayDescription": null,
          "SportId": null,
          "matchDay": 1,
          "Longitude": "174.8997797",
          "Latitude": "-36.9053315",
          "Address": "2 Bells Avenue",
          "Status": "POSTPONED",
          "CometScore": ""
        }
      ]
    }
    """;
  }
}
