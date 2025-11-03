package com.jordansimsmith.footballcalendar;

import biweekly.Biweekly;
import biweekly.component.VEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public class BiweeklySubfootballClient implements SubfootballClient {
  private static final String BASE_URL = "https://subfootball.com/teams/calendar/";
  private static final ZoneId AUCKLAND_ZONE = ZoneId.of("Pacific/Auckland");

  private final HttpClient httpClient;

  public BiweeklySubfootballClient(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  @Override
  public List<SubfootballFixture> getFixtures(String teamId) {
    try {
      return doGetFixtures(teamId);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private List<SubfootballFixture> doGetFixtures(String teamId) throws Exception {
    var icalContent = fetchIcal(teamId);
    var calendars = Biweekly.parse(icalContent).all();

    var fixtures = new ArrayList<SubfootballClient.SubfootballFixture>();

    for (var calendar : calendars) {
      for (var event : calendar.getEvents()) {
        var fixture = parseEvent(event);
        if (fixture != null) {
          fixtures.add(fixture);
        }
      }
    }

    return fixtures;
  }

  private String fetchIcal(String teamId) throws Exception {
    var url = BASE_URL + teamId;
    var request =
        HttpRequest.newBuilder()
            .uri(new URI(url))
            .header("Accept", "text/calendar")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like"
                    + " Gecko) Chrome/142.0.0.0 Safari/537.36")
            .GET()
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException("Failed to fetch iCal: " + response.statusCode());
    }

    return response.body();
  }

  private SubfootballClient.SubfootballFixture parseEvent(VEvent event) {
    var uid = event.getUid();
    if (uid == null || uid.getValue() == null) {
      return null;
    }

    var summary = event.getSummary();
    if (summary == null || summary.getValue() == null) {
      return null;
    }

    var dtStart = event.getDateStart();
    if (dtStart == null || dtStart.getValue() == null) {
      return null;
    }

    var location = event.getLocation();
    if (location == null || location.getValue() == null) {
      return null;
    }

    var summaryText = summary.getValue();
    var teams = parseTeamsFromSummary(summaryText);
    if (teams == null) {
      return null;
    }

    var timestamp =
        dtStart
            .getValue()
            .toInstant()
            .atZone(ZoneId.of("UTC"))
            .withZoneSameLocal(AUCKLAND_ZONE)
            .toInstant();
    var venue = location.getValue();

    return new SubfootballClient.SubfootballFixture(
        uid.getValue(), teams[0], teams[1], timestamp, venue);
  }

  private String[] parseTeamsFromSummary(String summary) {
    var parts = summary.split(" vs ");
    if (parts.length != 2) {
      return null;
    }

    var homeTeam = parts[0].trim();
    var awayTeam = parts[1].trim();

    var dashIndex = homeTeam.indexOf(" - ");
    if (dashIndex != -1) {
      homeTeam = homeTeam.substring(dashIndex + 3).trim();
    }

    return new String[] {homeTeam, awayTeam};
  }
}
