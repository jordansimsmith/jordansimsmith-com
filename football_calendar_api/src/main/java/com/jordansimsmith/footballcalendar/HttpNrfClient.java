package com.jordansimsmith.footballcalendar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

public class HttpNrfClient implements NrfClient {
  private static final String FIXTURE_DATES_PATH = "/api/v2/competition/widget/fixture/Dates";

  private static final ZoneId AUCKLAND_ZONE = ZoneId.of("Pacific/Auckland");
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final URI baseUri;

  public HttpNrfClient(HttpClient httpClient, ObjectMapper objectMapper, URI baseUri) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.baseUri = baseUri;
  }

  @Override
  public List<NrfFixture> findFixtures(
      List<Integer> compIds,
      List<Integer> orgIds,
      List<Integer> gradeIds,
      Instant from,
      Instant to) {
    try {
      return doGetFixtures(compIds, orgIds, gradeIds, from, to);
    } catch (Exception e) {
      throw new RuntimeException("Failed to get football fixtures", e);
    }
  }

  private List<NrfFixture> doGetFixtures(
      List<Integer> compIds, List<Integer> orgIds, List<Integer> gradeIds, Instant from, Instant to)
      throws Exception {

    var fromLocal = LocalDateTime.ofInstant(from, AUCKLAND_ZONE).format(DATE_FORMATTER);
    var toLocal = LocalDateTime.ofInstant(to, AUCKLAND_ZONE).format(DATE_FORMATTER);

    var request = new FixtureRequest(compIds, orgIds, gradeIds, fromLocal, toLocal);
    var requestBody = objectMapper.writeValueAsString(request);

    var httpRequest =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve(FIXTURE_DATES_PATH))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like"
                    + " Gecko) Chrome/135.0.0.0 Safari/537.36")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

    var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException("API request failed with response: " + response.body());
    }

    var fixtureResponse = objectMapper.readValue(response.body(), FixtureResponse.class);

    return fixtureResponse.fixtures().stream()
        .map(this::mapToNrfFixture)
        .collect(Collectors.toList());
  }

  private NrfFixture mapToNrfFixture(Fixture fixture) {
    var localDateTime = LocalDateTime.parse(fixture.from(), DATE_FORMATTER);
    var timestamp = localDateTime.atZone(AUCKLAND_ZONE).toInstant();

    var homeTeamName = fixture.homeOrgName() + " " + fixture.homeTeamName();
    var awayTeamName = fixture.awayOrgName() + " " + fixture.awayTeamName();

    return new NrfFixture(
        String.valueOf(fixture.id()),
        homeTeamName,
        awayTeamName,
        timestamp,
        fixture.venueName(),
        fixture.venueAddress(),
        fixture.locationLat(),
        fixture.locationLng(),
        fixture.statusName());
  }

  private record FixtureRequest(
      @JsonProperty("CompIds") List<Integer> compIds,
      @JsonProperty("OrgIds") List<Integer> orgIds,
      @JsonProperty("GradeIds") List<Integer> gradeIds,
      @JsonProperty("From") String from,
      @JsonProperty("To") String to) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record FixtureResponse(@JsonProperty("Fixtures") List<Fixture> fixtures) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Fixture(
      @JsonProperty("Id") int id,
      @JsonProperty("HomeTeamName") String homeTeamName,
      @JsonProperty("HomeOrgName") String homeOrgName,
      @JsonProperty("AwayTeamName") String awayTeamName,
      @JsonProperty("AwayOrgName") String awayOrgName,
      @JsonProperty("From") String from,
      @JsonProperty("VenueName") String venueName,
      @JsonProperty("VenueAddress") String venueAddress,
      @JsonProperty("LocationLat") Double locationLat,
      @JsonProperty("LocationLng") Double locationLng,
      @JsonProperty("StatusName") String statusName) {}
}
