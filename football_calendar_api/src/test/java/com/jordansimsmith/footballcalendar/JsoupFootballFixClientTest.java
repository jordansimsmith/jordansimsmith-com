package com.jordansimsmith.footballcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

public class JsoupFootballFixClientTest {
  @Test
  void parseFixtureShouldExtractAllFieldsCorrectly() {
    // arrange
    var html =
        """
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
        """;

    // act
    var doc = Jsoup.parse(html);
    var tables = doc.select("table.FTable");
    var firstTable = tables.first();
    var rows = firstTable.select("tr");

    var dateRow = rows.get(0);
    assertThat(dateRow.hasClass("FHeader")).isTrue();

    var fixtureRow = rows.get(1);
    assertThat(fixtureRow.hasClass("FRow")).isTrue();

    // assert
    var timeText = fixtureRow.select("td.FDate").text().trim();
    assertThat(timeText).isEqualTo("7:20pm");

    var venue = fixtureRow.select("td.FPlayingArea").text().trim();
    assertThat(venue).isEqualTo("Field 1");

    var homeTeam = fixtureRow.select("td.FHomeTeam").text().trim();
    assertThat(homeTeam).isEqualTo("Jesus and the Shepherds");

    var awayTeam = fixtureRow.select("td.FAwayTeam").text().trim();
    assertThat(awayTeam).isEqualTo("G-Raves RC");

    var scoreElement = fixtureRow.select("td.FScore nobr").first();
    assertThat(scoreElement).isNotNull();

    var fixtureId = scoreElement.attr("data-fixture-id");
    assertThat(fixtureId).isEqualTo("148618");
  }

  @Test
  void parseDateShouldHandleVariousFormats() {
    // arrange
    var html =
        """
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
        </table>
        """;

    // act
    var doc = Jsoup.parse(html);
    var dateText = doc.select("tr.FHeader td").first().text();

    // assert
    assertThat(dateText).isEqualTo("Thursday 23 Oct 2025");
  }

  @Test
  void parseTimeShouldHandleVariousFormats() {
    // arrange
    var html =
        """
        <table class="FTable">
          <tr class="FHeader">
            <td colspan="5">Thursday 06 Nov 2025</td>
          </tr>
          <tr class="FRow FBand">
            <td class="FDate">6:40pm</td>
            <td class="FPlayingArea">Field 2<br /></td>
            <td class="FHomeTeam"><a href="#">Lad FC</a></td>
            <td class="FScore"><div><nobr data-fixture-id="148623">vs</nobr></div></td>
            <td class="FAwayTeam"><a href="#">G-Raves RC</a></td>
          </tr>
        </table>
        """;

    // act
    var doc = Jsoup.parse(html);
    var timeText = doc.select("td.FDate").first().text();

    // assert
    assertThat(timeText).isEqualTo("6:40pm");
  }

  @Test
  void parseMultipleFixturesShouldReturnAllFixtures() {
    // arrange
    var html =
        """
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
        """;

    // act
    var doc = Jsoup.parse(html);
    var tables = doc.select("table.FTable");

    // assert
    assertThat(tables).hasSize(2);

    var firstTable = tables.get(0);
    var firstTableRows = firstTable.select("tr.FRow");
    assertThat(firstTableRows).hasSize(3);

    var secondTable = tables.get(1);
    var secondTableRows = secondTable.select("tr.FRow");
    assertThat(secondTableRows).hasSize(3);
  }
}
