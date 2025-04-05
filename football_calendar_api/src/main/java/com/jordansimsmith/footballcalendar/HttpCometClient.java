package com.jordansimsmith.footballcalendar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public class HttpCometClient implements CometClient {
  private static final String API_URL =
      "https://www.nrf.org.nz/api/1.0/competition/cometwidget/filteredfixtures";
  private static final String SPORT_ID = "1";

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public HttpCometClient(HttpClient httpClient, ObjectMapper objectMapper) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<FootballFixture> getFixtures(
      String seasonId,
      String competitionId,
      List<String> organisationIds,
      Instant from,
      Instant to) {
    try {
      return doGetFixtures(seasonId, competitionId, organisationIds, from, to);
    } catch (Exception e) {
      throw new RuntimeException("Failed to get football fixtures", e);
    }
  }

  private List<FootballFixture> doGetFixtures(
      String seasonId, String competitionId, List<String> organisationIds, Instant from, Instant to)
      throws Exception {

    var organizationIdsCsv = String.join(",", organisationIds);

    var request =
        new FixtureRequest(
            competitionId,
            organizationIdsCsv,
            from.toString(),
            to.toString(),
            SPORT_ID,
            seasonId,
            "",
            "",
            "",
            null,
            false,
            null,
            null,
            "True");

    var requestBody = objectMapper.writeValueAsString(request);

    var httpRequest =
        HttpRequest.newBuilder()
            .uri(new URI(API_URL))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

    var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException("API request failed with status: " + response.statusCode());
    }

    var fixtureResponse = objectMapper.readValue(response.body(), FixtureResponse.class);

    return fixtureResponse.fixtures().stream()
        .map(this::mapToFootballFixture)
        .collect(Collectors.toList());
  }

  private FootballFixture mapToFootballFixture(Fixture fixture) {
    Double latitude = null;
    Double longitude = null;

    if (fixture.latitude() != null && !fixture.latitude().isBlank()) {
      try {
        latitude = Double.parseDouble(fixture.latitude());
      } catch (NumberFormatException e) {
        // Ignore parsing errors
      }
    }

    if (fixture.longitude() != null && !fixture.longitude().isBlank()) {
      try {
        longitude = Double.parseDouble(fixture.longitude());
      } catch (NumberFormatException e) {
        // Ignore parsing errors
      }
    }

    var timestamp = Instant.parse(fixture.date());

    return new FootballFixture(
        fixture.id(),
        fixture.homeTeamNameAbbr(),
        fixture.awayTeamNameAbbr(),
        timestamp,
        fixture.venueName(),
        fixture.address(),
        latitude,
        longitude,
        fixture.status());
  }

  private record FixtureRequest(
      @JsonProperty("competitionId") String competitionId,
      @JsonProperty("orgIds") String orgIds,
      @JsonProperty("from") String from,
      @JsonProperty("to") String to,
      @JsonProperty("sportId") String sportId,
      @JsonProperty("seasonId") String seasonId,
      @JsonProperty("gradeIds") String gradeIds,
      @JsonProperty("gradeId") String gradeId,
      @JsonProperty("organisationId") String organisationId,
      @JsonProperty("roundId") String roundId,
      @JsonProperty("roundsOn") boolean roundsOn,
      @JsonProperty("matchDay") String matchDay,
      @JsonProperty("phaseId") String phaseId,
      @JsonProperty("logos") String logos) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record FixtureResponse(@JsonProperty("fixtures") List<Fixture> fixtures) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Fixture(
      @JsonProperty("Id") String id,
      @JsonProperty("HomeTeamNameAbbr") String homeTeamNameAbbr,
      @JsonProperty("AwayTeamNameAbbr") String awayTeamNameAbbr,
      @JsonProperty("Date") String date,
      @JsonProperty("VenueName") String venueName,
      @JsonProperty("Address") String address,
      @JsonProperty("Latitude") String latitude,
      @JsonProperty("Longitude") String longitude,
      @JsonProperty("Status") String status) {}
}
