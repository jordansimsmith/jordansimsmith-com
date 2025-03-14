package com.jordansimsmith.eventcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JsoupGoMediaEventClientTest {
  private static final String BASE_URL = "https://www.aucklandstadiums.co.nz";
  private static final String STADIUM_URL = BASE_URL + "/our-venues/go-media-stadium";
  private static final String MAIN_PAGE_HTML =
      """
      <html>
        <body>
          <article class="new-tile new-tile-event">
            <h5>Warriors vs Storm</h5>
            <p class="event-date">Friday 25 March</p>
            <p class="location">Go Media Stadium</p>
            <a class="new-tile-inner" href="/event/warriors-storm"></a>
          </article>
          <article class="new-tile new-tile-event">
            <h5>Taylor Swift Concert</h5>
            <p class="event-date">Monday 15 April</p>
            <p class="location">Go Media Stadium</p>
            <a class="new-tile-inner" href="/event/taylor-swift"></a>
          </article>
        </body>
      </html>
      """;

  private static final String EVENT_PAGE_HTML =
      """
      <html>
        <body>
          <h1 class="event-hero-carousel-heading">Warriors vs Storm</h1>
          <span class="event-hero-carousel-detail">Friday 25 March</span>
          <ul>
            <li>Kick off 7:30pm</li>
          </ul>
          <div class="event-summary">
            <ul>
              <li>Box office opens at 5:30PM, Gates open at 6:30PM</li>
            </ul>
          </div>
        </body>
      </html>
      """;

  private JsoupGoMediaEventClient client;

  @BeforeEach
  void setUp() {
    client =
        new JsoupGoMediaEventClient() {
          @Override
          protected Document fetchDocument(String url) throws IOException {
            if (url.equals(STADIUM_URL)) {
              return Jsoup.parse(MAIN_PAGE_HTML);
            } else if (url.equals(BASE_URL + "/event/warriors-storm")) {
              return Jsoup.parse(EVENT_PAGE_HTML);
            }
            throw new IOException("Unknown URL: " + url);
          }
        };
  }

  @Test
  void getEventsExtractsEventsFromMainPage() {
    // arrange
    var expectedStartTime = LocalDateTime.of(2024, 3, 25, 19, 30).toInstant(ZoneOffset.UTC);

    // act
    var events = client.getEvents();

    // assert
    assertThat(events).hasSize(2);

    var warriors = events.get(0);
    assertThat(warriors.title()).isEqualTo("Warriors vs Storm");
    assertThat(warriors.stadiumUrl()).isEqualTo(STADIUM_URL);
    assertThat(warriors.eventUrl()).isEqualTo(BASE_URL + "/event/warriors-storm");
    assertThat(warriors.startTime()).isEqualTo(expectedStartTime);
    assertThat(warriors.eventInfo()).isEqualTo("Box office opens at 5:30PM, Gates open at 6:30PM");
  }

  @Test
  void getEventsHandlesSeasonOverviewPages() {
    // arrange
    var seasonPageHtml =
        """
        <html>
          <body>
            <ul>
              <li>
                Friday 25 March - Kick off 7:30pm
                <a href="/event/warriors-storm">Warriors vs Storm</a>
              </li>
              <li>
                Saturday 30 March - Kick off 5:00pm
                <a href="/event/warriors-broncos">Warriors vs Broncos</a>
              </li>
            </ul>
          </body>
        </html>
        """;

    client =
        new JsoupGoMediaEventClient() {
          @Override
          protected Document fetchDocument(String url) throws IOException {
            if (url.equals(STADIUM_URL)) {
              return Jsoup.parse(
                  """
              <html>
                <body>
                  <article class="new-tile new-tile-event">
                    <h5>Warriors 2024 Home Season</h5>
                    <p class="event-date">March - September</p>
                    <p class="location">Go Media Stadium</p>
                    <a class="new-tile-inner" href="/home-season-2024"></a>
                  </article>
                </body>
              </html>
              """);
            } else if (url.equals(BASE_URL + "/home-season-2024")) {
              return Jsoup.parse(seasonPageHtml);
            }
            throw new IOException("Unknown URL: " + url);
          }
        };

    // act
    var events = client.getEvents();

    // assert
    assertThat(events).hasSize(2);

    var warriors = events.get(0);
    assertThat(warriors.title()).isEqualTo("Warriors vs Storm");
    assertThat(warriors.stadiumUrl()).isEqualTo(STADIUM_URL);
    assertThat(warriors.eventUrl()).isEqualTo(BASE_URL + "/event/warriors-storm");
    assertThat(warriors.startTime())
        .isEqualTo(LocalDateTime.of(2024, 3, 25, 19, 30).toInstant(ZoneOffset.UTC));

    var broncos = events.get(1);
    assertThat(broncos.title()).isEqualTo("Warriors vs Broncos");
    assertThat(broncos.stadiumUrl()).isEqualTo(STADIUM_URL);
    assertThat(broncos.eventUrl()).isEqualTo(BASE_URL + "/event/warriors-broncos");
    assertThat(broncos.startTime())
        .isEqualTo(LocalDateTime.of(2024, 3, 30, 17, 0).toInstant(ZoneOffset.UTC));
  }
}
