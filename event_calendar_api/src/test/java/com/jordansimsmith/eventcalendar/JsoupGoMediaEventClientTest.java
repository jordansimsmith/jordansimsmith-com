package com.jordansimsmith.eventcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JsoupGoMediaEventClientTest {
  private static final String BASE_URL = "https://www.aucklandstadiums.co.nz";
  private static final String STADIUM_URL = BASE_URL + "/our-venues/go-media-stadium";
  private static final ZoneId AUCKLAND_ZONE = ZoneId.of("Pacific/Auckland");
  private static final String MAIN_PAGE_HTML =
      """
      <html>
        <body>
          <article class="new-tile new-tile-event">
            <h5>One NZ Warriors v Sea Eagles 2025 Season</h5>
            <p class="event-date">Friday 14 March</p>
            <p class="location">Go Media Stadium</p>
            <a class="new-tile-inner" href="/event/one-nz-warriors-v-sea-eagles-2025-season"></a>
            <div class="event-summary">
              <ul>
                <li>Box office opens at 2PM, Gates open at 5PM</li>
              </ul>
            </div>
          </article>
          <article class="new-tile new-tile-event">
            <h5>One NZ Warriors 2025 NRL Home Season</h5>
            <p class="event-date">March - September</p>
            <p class="location">Go Media Stadium</p>
            <a class="new-tile-inner" href="/event/one-nz-warriors-2025-nrl-home-season"></a>
          </article>
        </body>
      </html>
      """;

  private static final String SEASON_PAGE_HTML =
      """
      <html>
        <body>
          <h1>One NZ Warriors 2025 NRL Home Season</h1>
          <div class="season-games">
            <ul>
              <li>
                <a href="/event/one-nz-warriors-v-sea-eagles-2025-season">One NZ Warriors v Sea Eagles 2025 Season</a>
                <span class="event-hero-carousel-detail">14 March 2025</span>
                <div class="event-summary"><ul><li>Kick off at 8PM</li></ul></div>
              </li>
              <li>
                <a href="/event/one-nz-warriors-v-roosters-2025-season">One NZ Warriors v Roosters 2025 Season</a>
                <span class="event-hero-carousel-detail">21 March 2025</span>
                <div class="event-summary"><ul><li>Kick off at 8PM</li></ul></div>
              </li>
              <li>
                <a href="/event/one-nz-warriors-v-broncos-2025-season">One NZ Warriors v Broncos 2025 Season</a>
                <span class="event-hero-carousel-detail">19 April 2025</span>
                <div class="event-summary"><ul><li>Kick off at 7:30PM</li></ul></div>
              </li>
            </ul>
          </div>
        </body>
      </html>
      """;

  private static final String SEA_EAGLES_EVENT_PAGE_HTML =
      """
      <html>
        <body>
          <h1 class="event-hero-carousel-heading">One NZ Warriors v Sea Eagles 2025 Season</h1>
          <span class="event-hero-carousel-detail">
            <svg width="40" height="40" viewBox="0 0 40 40" aria-hidden="true" focusable="true" name="event-calendar" class="icon" type="default">
              <title>Event Calendar</title>
              <use xlink:href="/static/assets/images/sprite.89806abf415d74b426d4.svg#event-calendar"></use>
            </svg>
            14 March 2025
          </span>
          <span class="event-hero-carousel-detail icon">
            <svg width="40" height="40" viewBox="0 0 40 40" aria-hidden="true" focusable="true" name="location" class="icon" type="default">
              <title>Location</title>
              <use xlink:href="/static/assets/images/sprite.89806abf415d74b426d4.svg#location"></use>
            </svg>
            Go Media Stadium
          </span>
          <div class="event-summary">
            <ul>
              <li>Box Office opens at 2PM, Gates open at 5PM</li>
              <li>NSW Cup Kick off at 5:15pm</li>
              <li>NRL Kick off at 8:00PM</li>
            </ul>
          </div>
        </body>
      </html>
      """;

  private static final String ROOSTERS_EVENT_PAGE_HTML =
      """
      <html>
        <body>
          <h1 class="event-hero-carousel-heading">One NZ Warriors v Roosters 2025 Season</h1>
          <span class="event-hero-carousel-detail">
            <svg width="40" height="40" viewBox="0 0 40 40" aria-hidden="true" focusable="true" name="event-calendar" class="icon" type="default">
              <title>Event Calendar</title>
              <use xlink:href="/static/assets/images/sprite.89806abf415d74b426d4.svg#event-calendar"></use>
            </svg>
            21 March 2025
          </span>
          <span class="event-hero-carousel-detail icon">
            <svg width="40" height="40" viewBox="0 0 40 40" aria-hidden="true" focusable="true" name="location" class="icon" type="default">
              <title>Location</title>
              <use xlink:href="/static/assets/images/sprite.89806abf415d74b426d4.svg#location"></use>
            </svg>
            Go Media Stadium
          </span>
          <div class="event-summary">
            <ul>
              <li>Box Office opens at 2 PM, Gates open at 5PM</li>
              <li>NSW Cup Kick off at 5:15 PM</li>
              <li>NRL Kick off at 8PM</li>
            </ul>
          </div>
        </body>
      </html>
      """;

  private static final String BRONCOS_EVENT_PAGE_HTML =
      """
      <html>
        <body>
          <h1 class="event-hero-carousel-heading">One NZ Warriors v Broncos 2025 Season</h1>
          <span class="event-hero-carousel-detail">
            <svg width="40" height="40" viewBox="0 0 40 40" aria-hidden="true" focusable="true" name="event-calendar" class="icon" type="default">
              <title>Event Calendar</title>
              <use xlink:href="/static/assets/images/sprite.89806abf415d74b426d4.svg#event-calendar"></use>
            </svg>
            19 April 2025
          </span>
          <span class="event-hero-carousel-detail icon">
            <svg width="40" height="40" viewBox="0 0 40 40" aria-hidden="true" focusable="true" name="location" class="icon" type="default">
              <title>Location</title>
              <use xlink:href="/static/assets/images/sprite.89806abf415d74b426d4.svg#location"></use>
            </svg>
            Go Media Stadium
          </span>
          <div class="event-summary">
            <ul>
              <li>Box Office opens at 2pm, Gates open at 5pm</li>
              <li>NSW Cup Kick off at 5pm</li>
              <li>NRL Kick off at 7:30pm</li>
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
          protected Document fetchDocument(String url) {
            return switch (url) {
              case STADIUM_URL -> Jsoup.parse(MAIN_PAGE_HTML);
              case BASE_URL + "/event/one-nz-warriors-2025-nrl-home-season" -> Jsoup.parse(
                  SEASON_PAGE_HTML);
              case BASE_URL + "/event/one-nz-warriors-v-sea-eagles-2025-season" -> Jsoup.parse(
                  SEA_EAGLES_EVENT_PAGE_HTML);
              case BASE_URL + "/event/one-nz-warriors-v-roosters-2025-season" -> Jsoup.parse(
                  ROOSTERS_EVENT_PAGE_HTML);
              case BASE_URL + "/event/one-nz-warriors-v-broncos-2025-season" -> Jsoup.parse(
                  BRONCOS_EVENT_PAGE_HTML);
              default -> throw new AssertionError("Unexpected URL in test: " + url);
            };
          }
        };
  }

  @Test
  void getEventsExtractsEventsFromMainPage() {
    // arrange & act
    var events = client.getEvents();

    // assert
    assertThat(events).hasSize(3);

    // verify sea eagles event
    var seaEaglesStartTime = LocalDateTime.of(2025, 3, 14, 20, 0).atZone(AUCKLAND_ZONE).toInstant();
    var seaEaglesEvent =
        events.stream().filter(e -> e.title().contains("Sea Eagles")).findFirst().orElseThrow();
    assertThat(seaEaglesEvent)
        .satisfies(
            e -> {
              assertThat(e.title()).isEqualTo("One NZ Warriors v Sea Eagles 2025 Season");
              assertThat(e.stadiumUrl()).isEqualTo(STADIUM_URL);
              assertThat(e.eventUrl())
                  .isEqualTo(BASE_URL + "/event/one-nz-warriors-v-sea-eagles-2025-season");
              assertThat(e.startTime()).isEqualTo(seaEaglesStartTime);
              assertThat(e.eventInfo()).contains("Box Office opens at 2PM", "Gates open at 5PM");
            });

    // verify roosters event
    var roostersStartTime = LocalDateTime.of(2025, 3, 21, 20, 0).atZone(AUCKLAND_ZONE).toInstant();
    var roostersEvent =
        events.stream().filter(e -> e.title().contains("Roosters")).findFirst().orElseThrow();
    assertThat(roostersEvent)
        .satisfies(
            e -> {
              assertThat(e.title()).isEqualTo("One NZ Warriors v Roosters 2025 Season");
              assertThat(e.stadiumUrl()).isEqualTo(STADIUM_URL);
              assertThat(e.eventUrl())
                  .isEqualTo(BASE_URL + "/event/one-nz-warriors-v-roosters-2025-season");
              assertThat(e.startTime()).isEqualTo(roostersStartTime);
              assertThat(e.eventInfo()).contains("Box Office opens at 2 PM", "Gates open at 5PM");
            });

    // verify broncos event
    var broncosStartTime = LocalDateTime.of(2025, 4, 19, 19, 30).atZone(AUCKLAND_ZONE).toInstant();
    var broncosEvent =
        events.stream().filter(e -> e.title().contains("Broncos")).findFirst().orElseThrow();
    assertThat(broncosEvent)
        .satisfies(
            e -> {
              assertThat(e.title()).isEqualTo("One NZ Warriors v Broncos 2025 Season");
              assertThat(e.stadiumUrl()).isEqualTo(STADIUM_URL);
              assertThat(e.eventUrl())
                  .isEqualTo(BASE_URL + "/event/one-nz-warriors-v-broncos-2025-season");
              assertThat(e.startTime()).isEqualTo(broncosStartTime);
              assertThat(e.eventInfo()).contains("Box Office opens at 2pm", "Gates open at 5pm");
            });
  }

  @Test
  void shouldDefaultToMidnightWhenNoTimeInfoAvailable() {
    // arrange - using real HTML from
    // https://www.aucklandstadiums.co.nz/event/kiwis-and-kiwi-ferns-vs-samoa-2025
    var mainPageWithKiwisEventHtml =
        """
        <html>
          <body>
            <article class="new-tile-event">
              <h5>Kiwis and Kiwi Ferns vs Samoa 2025</h5>
              <p class="event-date">19 October 2025</p>
              <p class="location">Go Media Stadium</p>
              <a class="new-tile-inner" href="/event/kiwis-and-kiwi-ferns-vs-samoa-2025"></a>
            </article>
          </body>
        </html>
        """;

    var kiwisEventPageHtml =
        """
        <html>
          <body>
            <h1 class="event-hero-carousel-heading">Kiwis and Kiwi Ferns vs Samoa 2025</h1>
            <span class="event-hero-carousel-detail">
              <svg width="40" height="40" viewBox="0 0 40 40" aria-hidden="true" focusable="true" name="event-calendar" class="icon" type="default">
                <title>Event Calendar</title>
                <use xlink:href="/static/assets/images/sprite.89806abf415d74b426d4.svg#event-calendar"></use>
              </svg>
              19 October 2025
            </span>
            <span class="event-hero-carousel-detail icon">
              <svg width="40" height="40" viewBox="0 0 40 40" aria-hidden="true" focusable="true" name="location" class="icon" type="default">
                <title>Location</title>
                <use xlink:href="/static/assets/images/sprite.89806abf415d74b426d4.svg#location"></use>
              </svg>
              Go Media Stadium
            </span>
            <div class="event-summary">
              <ul>
                <li style='background-image: url(/static/assets/images/accessible-seating.e6474850906ba2f3fb72.svg)'>Accessible seating available</li>
                <li style='background-image: url(/static/assets/images/bag-restrictions.24f533148fd11e62ac3f.svg)'>Bag size restrictions apply</li>
              </ul>
            </div>
          </body>
        </html>
        """;

    JsoupGoMediaEventClient testClient =
        new JsoupGoMediaEventClient() {
          @Override
          protected Document fetchDocument(String url) {
            if (url.equals(STADIUM_URL)) {
              return Jsoup.parse(mainPageWithKiwisEventHtml);
            } else if (url.equals(BASE_URL + "/event/kiwis-and-kiwi-ferns-vs-samoa-2025")) {
              return Jsoup.parse(kiwisEventPageHtml);
            } else {
              throw new AssertionError("Unexpected URL in test: " + url);
            }
          }
        };

    // act
    var events = testClient.getEvents();

    // assert
    // Should default to 00:00 (midnight) when no time information is available
    assertThat(events).hasSize(1);
    var expectedTime = LocalDateTime.of(2025, 10, 19, 0, 0).atZone(AUCKLAND_ZONE).toInstant();
    assertThat(events.get(0).startTime()).isEqualTo(expectedTime);
    assertThat(events.get(0).title()).isEqualTo("Kiwis and Kiwi Ferns vs Samoa 2025");
    assertThat(events.get(0).eventInfo())
        .isEqualTo("Accessible seating available, Bag size restrictions apply");
  }

  @Test
  void shouldSkipInvalidTimesInEventInfo() {
    // arrange
    var mainPageWithEventHtml =
        """
        <html>
          <body>
            <article class="new-tile-event">
              <h5>Event With Mixed Times</h5>
              <p class="event-date">1 January 2025</p>
              <p class="location">Go Media Stadium</p>
              <a class="new-tile-inner" href="/event/event-with-mixed-times"></a>
            </article>
          </body>
        </html>
        """;

    var invalidTimeEventHtml =
        """
        <html>
          <body>
            <h1 class="event-hero-carousel-heading">Event With Mixed Times</h1>
            <span class="event-hero-carousel-detail">
              <svg name="event-calendar"></svg>
              1 January 2025
            </span>
            <div class="event-summary">
              <ul>
                <li>Box Office opens at 2pm</li>
                <li>Early entry at 12 30pm</li>
                <li>Main event at 8pm</li>
              </ul>
            </div>
          </body>
        </html>
        """;

    JsoupGoMediaEventClient testClient =
        new JsoupGoMediaEventClient() {
          @Override
          protected Document fetchDocument(String url) {
            if (url.equals(STADIUM_URL)) {
              return Jsoup.parse(mainPageWithEventHtml);
            } else if (url.equals(BASE_URL + "/event/event-with-mixed-times")) {
              return Jsoup.parse(invalidTimeEventHtml);
            } else {
              throw new AssertionError("Unexpected URL in test: " + url);
            }
          }
        };

    // act
    var events = testClient.getEvents();

    // assert
    // Should select 8pm as the latest valid time, skipping the invalid "12 30pm"
    assertThat(events).hasSize(1);
    var expectedTime = LocalDateTime.of(2025, 1, 1, 20, 0).atZone(AUCKLAND_ZONE).toInstant();
    assertThat(events.get(0).startTime()).isEqualTo(expectedTime);
  }
}
