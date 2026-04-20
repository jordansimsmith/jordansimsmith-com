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
import java.net.URI;
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

public class HttpNrfClientTest {
  @Mock HttpClient mockHttpClient;

  private HttpNrfClient client;

  private AutoCloseable openMocks;

  @BeforeEach
  void setUp() {
    openMocks = openMocks(this);
    var objectMapper = new ObjectMapper();
    client = new HttpNrfClient(mockHttpClient, objectMapper, URI.create("https://www.nrf.org.nz"));
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

    var compIds = List.of(12869);
    var orgIds = List.of(9701);
    var gradeIds = List.of(721150);
    var from = Instant.parse("2026-04-18T00:00:00Z");
    var to = Instant.parse("2026-05-08T23:59:00Z");

    // act
    var fixtures = client.findFixtures(compIds, orgIds, gradeIds, from, to);

    // assert
    assertThat(fixtures).isNotNull();
    assertThat(fixtures).hasSize(1);
    var fixture = fixtures.get(0);
    assertThat(fixture.id()).isEqualTo("6334635");
    assertThat(fixture.homeTeamName()).isEqualTo("Bucklands Beach AFC Dusties");
    assertThat(fixture.awayTeamName()).isEqualTo("Ellerslie AFC Flamingos");
    var expectedLocalDateTime = LocalDateTime.of(2026, 4, 18, 13, 0, 0);
    var expectedInstant = expectedLocalDateTime.atZone(ZoneId.of("Pacific/Auckland")).toInstant();
    assertThat(fixture.timestamp()).isEqualTo(expectedInstant);
    assertThat(fixture.venue()).isEqualTo("Lloyd Elsmore Pk: Field 2");
    assertThat(fixture.address()).isEqualTo("Lloyd Elsmore Park");
    assertThat(fixture.latitude()).isEqualTo(-36.910553);
    assertThat(fixture.longitude()).isEqualTo(174.90271);
    assertThat(fixture.status()).isEqualTo("Confirmed");

    // verify request
    var requestCaptor = forClass(HttpRequest.class);
    verify(mockHttpClient).send(requestCaptor.capture(), any());
    var actualRequest = requestCaptor.getValue();
    assertThat(actualRequest.uri().toString())
        .isEqualTo("https://www.nrf.org.nz/api/v2/competition/widget/fixture/Dates");
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

    var compIds = List.of(12869);
    var orgIds = List.of(9701);
    var gradeIds = List.of(721150);
    var from = Instant.parse("2026-04-18T00:00:00Z");
    var to = Instant.parse("2026-05-08T23:59:00Z");

    // act & assert
    assertThrows(
        RuntimeException.class,
        () -> client.findFixtures(compIds, orgIds, gradeIds, from, to),
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
      "Fixtures": [
        {
          "Id": 6334635,
          "CompOrgId": 16131,
          "CompId": 12869,
          "SportId": 28,
          "From": "2026-04-18T13:00:00",
          "To": "2026-04-18T15:00:00",
          "HomeTeamId": 436026,
          "HomeTeamName": "Dusties",
          "HomeOrgName": "Bucklands Beach AFC",
          "HomeOrgAbbr": "Bucklands Beach AFC",
          "HomeOrganisationId": 27534,
          "AwayTeamId": 426366,
          "AwayTeamName": "Flamingos",
          "AwayOrgName": "Ellerslie AFC",
          "AwayOrgAbbr": "Ellerslie AFC",
          "AwayOrganisationId": 9701,
          "VenueId": "17034",
          "VenueName": "Lloyd Elsmore Pk: Field 2",
          "VenueAbbr": "Lloyd Elsmore Pk: 2",
          "VenueAddress": "Lloyd Elsmore Park",
          "LocationLat": -36.910553,
          "LocationLng": 174.90271,
          "GradeId": 721150,
          "GradeName": "NRF Men Div. 6 Central/East",
          "SectionId": 243702,
          "SectionName": "Section A",
          "RoundId": 637474,
          "RoundName": "ROUND 2",
          "PublishVenue": true,
          "PublishResults": true,
          "StatusName": "Confirmed",
          "Status": 2,
          "CssName": "fixture-confirmed",
          "ResultStatus": 0,
          "HomeScore": "1",
          "AwayScore": "2"
        }
      ]
    }
    """;
  }
}
