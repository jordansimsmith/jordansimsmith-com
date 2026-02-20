package com.jordansimsmith.footballcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

public class JsoupFootballFixClientTest {
  @Test
  void findFixturesShouldExtractAllFieldsCorrectly() {
    // arrange
    var html =
        """
        <html>
          <body>
            <table class="FTable">
              <tr class="FHeader">
                <td colspan="5">Thursday 23 Oct 2025</td>
              </tr>
              <tr class="FRow FBand">
                <td class="FDate">7:20pm</td>
                <td class="FPlayingArea">Field 1<br /></td>
                <td class="FHomeTeam"><a href="#">Jesus and the Shepherds</a></td>
                <td class="FScore"><div><nobr data-fixture-id="148618">vs</nobr></div></td>
                <td class="FAwayTeam"><a href="#">G-Raves RC</a></td>
              </tr>
              <tr class="FRow">
                <td class="FDate">8:40pm</td>
                <td class="FPlayingArea">Field 1<br /></td>
                <td class="FHomeTeam"><a href="#">ABCDE FC</a></td>
                <td class="FScore"><div><nobr data-fixture-id="148616">vs</nobr></div></td>
                <td class="FAwayTeam"><a href="#">Gaan Maro FC</a></td>
              </tr>
              <tr class="FRow FBand">
                <td class="FDate">8:40pm</td>
                <td class="FPlayingArea">Field 2<br /></td>
                <td class="FHomeTeam"><a href="#">Lad FC</a></td>
                <td class="FScore"><div><nobr data-fixture-id="148617">vs</nobr></div></td>
                <td class="FAwayTeam"><a href="#">Flamingoes</a></td>
              </tr>
            </table>
          </body>
        </html>
        """;
    var client =
        new JsoupFootballFixClient(URI.create("https://footballfix.spawtz.com")) {
          @Override
          protected Document fetchDocument(String url) {
            return Jsoup.parse(html);
          }
        };
    var venueId = "13";
    var leagueId = "131";
    var seasonId = "89";
    var divisionId = "6030";

    // act
    var fixtures = client.findFixtures(venueId, leagueId, seasonId, divisionId);

    // assert
    assertThat(fixtures).hasSize(3);

    var fixture1 = fixtures.stream().filter(f -> f.id().equals("148618")).findFirst().orElseThrow();
    assertThat(fixture1.homeTeamName()).isEqualTo("Jesus and the Shepherds");
    assertThat(fixture1.awayTeamName()).isEqualTo("G-Raves RC");
    assertThat(fixture1.venue()).isEqualTo("Field 1");

    var expectedInstant1 =
        ZonedDateTime.of(2025, 10, 23, 19, 20, 0, 0, ZoneId.of("Pacific/Auckland")).toInstant();
    assertThat(fixture1.timestamp()).isEqualTo(expectedInstant1);

    var fixture2 = fixtures.stream().filter(f -> f.id().equals("148616")).findFirst().orElseThrow();
    assertThat(fixture2.homeTeamName()).isEqualTo("ABCDE FC");
    assertThat(fixture2.awayTeamName()).isEqualTo("Gaan Maro FC");
    assertThat(fixture2.venue()).isEqualTo("Field 1");

    var expectedInstant2 =
        ZonedDateTime.of(2025, 10, 23, 20, 40, 0, 0, ZoneId.of("Pacific/Auckland")).toInstant();
    assertThat(fixture2.timestamp()).isEqualTo(expectedInstant2);

    var fixture3 = fixtures.stream().filter(f -> f.id().equals("148617")).findFirst().orElseThrow();
    assertThat(fixture3.homeTeamName()).isEqualTo("Lad FC");
    assertThat(fixture3.awayTeamName()).isEqualTo("Flamingoes");
    assertThat(fixture3.venue()).isEqualTo("Field 2");

    var expectedInstant3 =
        ZonedDateTime.of(2025, 10, 23, 20, 40, 0, 0, ZoneId.of("Pacific/Auckland")).toInstant();
    assertThat(fixture3.timestamp()).isEqualTo(expectedInstant3);
  }

  @Test
  void findFixturesShouldParseMultipleDates() {
    // arrange
    var html =
        """
        <html>
          <body>
            <table class="FTable">
              <tr class="FHeader">
                <td colspan="5">Thursday 23 Oct 2025</td>
              </tr>
              <tr class="FRow FBand">
                <td class="FDate">7:20pm</td>
                <td class="FPlayingArea">Field 1<br /></td>
                <td class="FHomeTeam"><a href="#">Jesus and the Shepherds</a></td>
                <td class="FScore"><div><nobr data-fixture-id="148618">vs</nobr></div></td>
                <td class="FAwayTeam"><a href="#">G-Raves RC</a></td>
              </tr>
              <tr class="FRow">
                <td class="FDate">8:40pm</td>
                <td class="FPlayingArea">Field 1<br /></td>
                <td class="FHomeTeam"><a href="#">ABCDE FC</a></td>
                <td class="FScore"><div><nobr data-fixture-id="148616">vs</nobr></div></td>
                <td class="FAwayTeam"><a href="#">Gaan Maro FC</a></td>
              </tr>
              <tr class="FRow FBand">
                <td class="FDate">8:40pm</td>
                <td class="FPlayingArea">Field 2<br /></td>
                <td class="FHomeTeam"><a href="#">Lad FC</a></td>
                <td class="FScore"><div><nobr data-fixture-id="148617">vs</nobr></div></td>
                <td class="FAwayTeam"><a href="#">Flamingoes</a></td>
              </tr>
            </table>
            <table class="FTable">
              <tr class="FHeader">
                <td colspan="5">Thursday 30 Oct 2025</td>
              </tr>
              <tr class="FRow FBand">
                <td class="FDate">7:20pm</td>
                <td class="FPlayingArea">Field 1<br /></td>
                <td class="FHomeTeam"><a href="#">Lad FC</a></td>
                <td class="FScore"><div><nobr data-fixture-id="148619">vs</nobr></div></td>
                <td class="FAwayTeam"><a href="#">Gaan Maro FC</a></td>
              </tr>
              <tr class="FRow">
                <td class="FDate">7:20pm</td>
                <td class="FPlayingArea">Field 2<br /></td>
                <td class="FHomeTeam"><a href="#">Flamingoes</a></td>
                <td class="FScore"><div><nobr data-fixture-id="148620">vs</nobr></div></td>
                <td class="FAwayTeam"><a href="#">G-Raves RC</a></td>
              </tr>
              <tr class="FRow FBand">
                <td class="FDate">7:20pm</td>
                <td class="FPlayingArea">Field 3<br /></td>
                <td class="FHomeTeam"><a href="#">ABCDE FC</a></td>
                <td class="FScore"><div><nobr data-fixture-id="148621">vs</nobr></div></td>
                <td class="FAwayTeam"><a href="#">Jesus and the Shepherds</a></td>
              </tr>
            </table>
          </body>
        </html>
        """;
    var client =
        new JsoupFootballFixClient(URI.create("https://footballfix.spawtz.com")) {
          @Override
          protected Document fetchDocument(String url) {
            return Jsoup.parse(html);
          }
        };
    var venueId = "13";
    var leagueId = "131";
    var seasonId = "89";
    var divisionId = "6030";

    // act
    var fixtures = client.findFixtures(venueId, leagueId, seasonId, divisionId);

    // assert
    assertThat(fixtures).hasSize(6);

    var oct23Fixtures =
        fixtures.stream()
            .filter(f -> f.timestamp().atZone(ZoneId.of("Pacific/Auckland")).getDayOfMonth() == 23)
            .toList();
    assertThat(oct23Fixtures).hasSize(3);

    var oct30Fixtures =
        fixtures.stream()
            .filter(f -> f.timestamp().atZone(ZoneId.of("Pacific/Auckland")).getDayOfMonth() == 30)
            .toList();
    assertThat(oct30Fixtures).hasSize(3);

    var oct30Fixture1 =
        fixtures.stream().filter(f -> f.id().equals("148619")).findFirst().orElseThrow();
    assertThat(oct30Fixture1.homeTeamName()).isEqualTo("Lad FC");
    assertThat(oct30Fixture1.awayTeamName()).isEqualTo("Gaan Maro FC");
  }

  @Test
  void findFixturesShouldParseVariousTimeFormats() {
    // arrange
    var html =
        """
        <html>
          <body>
            <table class="FTable">
              <tr class="FHeader">
                <td colspan="5">Thursday 06 Nov 2025</td>
              </tr>
              <tr class="FRow FBand">
                <td class="FDate">7:20pm</td>
                <td class="FPlayingArea">Field 1<br /></td>
                <td class="FHomeTeam"><a href="#">Home Team</a></td>
                <td class="FScore"><div><nobr data-fixture-id="1001">vs</nobr></div></td>
                <td class="FAwayTeam"><a href="#">Away Team</a></td>
              </tr>
              <tr class="FRow">
                <td class="FDate">9:15am</td>
                <td class="FPlayingArea">Field 2<br /></td>
                <td class="FHomeTeam"><a href="#">Morning Home</a></td>
                <td class="FScore"><div><nobr data-fixture-id="1002">vs</nobr></div></td>
                <td class="FAwayTeam"><a href="#">Morning Away</a></td>
              </tr>
              <tr class="FRow FBand">
                <td class="FDate">11:30AM</td>
                <td class="FPlayingArea">Field 3<br /></td>
                <td class="FHomeTeam"><a href="#">Uppercase Home</a></td>
                <td class="FScore"><div><nobr data-fixture-id="1003">vs</nobr></div></td>
                <td class="FAwayTeam"><a href="#">Uppercase Away</a></td>
              </tr>
              <tr class="FRow">
                <td class="FDate">6:00PM</td>
                <td class="FPlayingArea">Field 4<br /></td>
                <td class="FHomeTeam"><a href="#">Evening Home</a></td>
                <td class="FScore"><div><nobr data-fixture-id="1004">vs</nobr></div></td>
                <td class="FAwayTeam"><a href="#">Evening Away</a></td>
              </tr>
            </table>
          </body>
        </html>
        """;
    var client =
        new JsoupFootballFixClient(URI.create("https://footballfix.spawtz.com")) {
          @Override
          protected Document fetchDocument(String url) {
            return Jsoup.parse(html);
          }
        };
    var venueId = "13";
    var leagueId = "131";
    var seasonId = "89";
    var divisionId = "6030";

    // act
    var fixtures = client.findFixtures(venueId, leagueId, seasonId, divisionId);

    // assert
    assertThat(fixtures).hasSize(4);

    var pmLowercase =
        fixtures.stream().filter(f -> f.id().equals("1001")).findFirst().orElseThrow();
    var expectedPmLowercase =
        ZonedDateTime.of(2025, 11, 6, 19, 20, 0, 0, ZoneId.of("Pacific/Auckland")).toInstant();
    assertThat(pmLowercase.timestamp()).isEqualTo(expectedPmLowercase);

    var amLowercase =
        fixtures.stream().filter(f -> f.id().equals("1002")).findFirst().orElseThrow();
    var expectedAmLowercase =
        ZonedDateTime.of(2025, 11, 6, 9, 15, 0, 0, ZoneId.of("Pacific/Auckland")).toInstant();
    assertThat(amLowercase.timestamp()).isEqualTo(expectedAmLowercase);

    var amUppercase =
        fixtures.stream().filter(f -> f.id().equals("1003")).findFirst().orElseThrow();
    var expectedAmUppercase =
        ZonedDateTime.of(2025, 11, 6, 11, 30, 0, 0, ZoneId.of("Pacific/Auckland")).toInstant();
    assertThat(amUppercase.timestamp()).isEqualTo(expectedAmUppercase);

    var pmUppercase =
        fixtures.stream().filter(f -> f.id().equals("1004")).findFirst().orElseThrow();
    var expectedPmUppercase =
        ZonedDateTime.of(2025, 11, 6, 18, 0, 0, 0, ZoneId.of("Pacific/Auckland")).toInstant();
    assertThat(pmUppercase.timestamp()).isEqualTo(expectedPmUppercase);
  }
}
