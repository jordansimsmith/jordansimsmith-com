package com.jordansimsmith.eventcalendar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class HttpLeinsterRugbyClient implements LeinsterRugbyClient {

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public HttpLeinsterRugbyClient(HttpClient httpClient, ObjectMapper objectMapper) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<LeinsterFixture> findFixtures() {
    try {
      var request =
          HttpRequest.newBuilder().uri(URI.create(LeinsterRugbyClient.FIXTURES_URL)).GET().build();
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return toFixtures(response.body());
    } catch (Exception e) {
      throw new RuntimeException("Failed to fetch Leinster Rugby fixtures", e);
    }
  }

  private List<LeinsterFixture> toFixtures(String body) throws Exception {
    var fixtures = objectMapper.readValue(body, new TypeReference<List<FixtureResponse>>() {});

    var result = new ArrayList<LeinsterFixture>();
    for (var fixture : fixtures) {
      if (fixture == null
          || fixture.name() == null
          || fixture.id() == null
          || fixture.datetime() == null) {
        continue;
      }

      var competition = extractCompetition(fixture.stage());
      var venueName = fixture.venue() != null ? fixture.venue().name() : null;
      var venueCity = fixture.venue() != null ? fixture.venue().city() : null;
      var location = formatLocation(venueName, venueCity);

      result.add(
          new LeinsterFixture(
              fixture.id(),
              fixture.name(),
              Instant.parse(fixture.datetime()),
              competition,
              location));
    }

    return result;
  }

  private String formatLocation(String venueName, String venueCity) {
    var hasVenueName = !Strings.isNullOrEmpty(venueName);
    var hasVenueCity = !Strings.isNullOrEmpty(venueCity);

    if (hasVenueName && hasVenueCity) {
      return venueName + ", " + venueCity;
    }
    if (hasVenueName) {
      return venueName;
    }
    if (hasVenueCity) {
      return venueCity;
    }
    return null;
  }

  private String extractCompetition(Stage stage) {
    if (stage == null || stage.season() == null || stage.season().competition() == null) {
      return null;
    }
    return stage.season().competition().name();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record FixtureResponse(
      @JsonProperty("_name") String name,
      @JsonProperty("id") String id,
      @JsonProperty("datetime") String datetime,
      @JsonProperty("venue") Venue venue,
      @JsonProperty("stage") Stage stage) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Venue(@JsonProperty("_name") String name, @JsonProperty("city") String city) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Stage(@JsonProperty("season") Season season) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Season(@JsonProperty("competition") Competition competition) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Competition(@JsonProperty("name") String name) {}
}
