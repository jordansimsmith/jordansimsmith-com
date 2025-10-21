package com.jordansimsmith.footballcalendar;

import com.google.common.base.Preconditions;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

public class JsoupFootballFixClient implements FootballFixClient {
  private static final String BASE_URL = "https://footballfix.spawtz.com/Leagues/Fixtures";
  private static final int SPORT_ID = 0;
  private static final ZoneId AUCKLAND_ZONE = ZoneId.of("Pacific/Auckland");
  private static final DateTimeFormatter DATE_FORMATTER =
      DateTimeFormatter.ofPattern("EEEE dd MMM yyyy", Locale.ENGLISH);
  private static final DateTimeFormatter TIME_FORMATTER =
      DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH);

  private final String address;

  public JsoupFootballFixClient(String address) {
    this.address = address;
  }

  @Override
  public List<FootballFixClient.FootballFixture> getFixtures(
      String venueId, String leagueId, String seasonId, String divisionId) {
    try {
      return doGetFixtures(venueId, leagueId, seasonId, divisionId);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private List<FootballFixClient.FootballFixture> doGetFixtures(
      String venueId, String leagueId, String seasonId, String divisionId) throws Exception {
    var url =
        String.format(
            "%s?SportId=%d&VenueId=%s&LeagueId=%s&SeasonId=%s&DivisionId=%s",
            BASE_URL, SPORT_ID, venueId, leagueId, seasonId, divisionId);

    var doc = Jsoup.connect(url).get();
    List<FootballFixClient.FootballFixture> fixtures = new ArrayList<>();

    var tables = doc.select("table.FTable");
    for (var table : tables) {
      LocalDate currentDate = null;

      var rows = table.select("tr");
      for (var row : rows) {
        if (row.hasClass("FHeader")) {
          var firstCell = row.select("td").first();
          var dateText = firstCell.text();
          currentDate = LocalDate.parse(dateText, DATE_FORMATTER);
          continue;
        }

        if (row.hasClass("FRow")) {
          Preconditions.checkNotNull(currentDate, "Date header must precede fixture rows");

          var fixture = parseFixtureRow(row, currentDate);
          if (fixture != null) {
            fixtures.add(fixture);
          }
        }
      }
    }

    return fixtures;
  }

  private FootballFixClient.FootballFixture parseFixtureRow(Element row, LocalDate date) {
    var timeText = row.select("td.FDate").text().trim();
    var venue = row.select("td.FPlayingArea").text().trim();
    var homeTeam = row.select("td.FHomeTeam").text().trim();
    var awayTeam = row.select("td.FAwayTeam").text().trim();

    var scoreElement = row.select("td.FScore nobr").first();
    if (scoreElement == null) {
      return null;
    }

    var fixtureId = scoreElement.attr("data-fixture-id");
    if (fixtureId.isEmpty()) {
      return null;
    }

    var time = LocalTime.parse(timeText, TIME_FORMATTER);
    var zonedDateTime = ZonedDateTime.of(date, time, AUCKLAND_ZONE);
    var timestamp = zonedDateTime.toInstant();

    return new FootballFixClient.FootballFixture(
        fixtureId, homeTeam, awayTeam, timestamp, venue, address);
  }
}
