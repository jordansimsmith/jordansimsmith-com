package com.jordansimsmith.eventcalendar;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Verify;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsoupGoMediaEventClient implements GoMediaEventClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(JsoupGoMediaEventClient.class);

  private static final String BASE_URL = "https://www.aucklandstadiums.co.nz";
  private static final String STADIUM_URL = BASE_URL + "/our-venues/go-media-stadium";
  private static final ZoneId AUCKLAND_ZONE = ZoneId.of("Pacific/Auckland");
  private static final Pattern DATE_PATTERN = Pattern.compile("\\d{1,2}\\s+[A-Za-z]+\\s+\\d{4}");
  private static final Pattern TIME_PATTERN =
      Pattern.compile("\\d{1,2}(?::\\d{2})?\\s*(?:AM|PM)", Pattern.CASE_INSENSITIVE);
  private static final DateTimeFormatter DATE_FORMATTER =
      DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
  private static final DateTimeFormatter TIME_FORMATTER =
      new DateTimeFormatterBuilder()
          .parseCaseInsensitive()
          .appendPattern("h[:mm][ ]a") // Handles both h:mm a and ha formats
          .toFormatter(Locale.ENGLISH);

  @Override
  public List<GoMediaEvent> getEvents() {
    try {
      return doGetEvents();
    } catch (Exception e) {
      throw new RuntimeException("Failed to get events", e);
    }
  }

  private List<GoMediaEvent> doGetEvents() throws Exception {
    // get all event and season URLs from main page
    var mainPage = fetchDocument(STADIUM_URL);
    var eventUrls = new HashSet<String>();
    var seasonUrls = new HashSet<String>();

    for (var article : mainPage.select("article.new-tile-event")) {
      var url = article.select("a.new-tile-inner").attr("href");
      if (url.isEmpty()) {
        continue;
      }
      var absoluteUrl = BASE_URL + url;

      if (absoluteUrl.contains("home-season")) {
        seasonUrls.add(absoluteUrl);
      } else {
        eventUrls.add(absoluteUrl);
      }
    }

    // get all event URLs from season pages
    for (var seasonUrl : seasonUrls) {
      var seasonPage = fetchDocument(seasonUrl);
      for (var seasonEvent : seasonPage.select(".season-games li")) {
        var seasonEventUrl = seasonEvent.select("a").attr("href");
        if (seasonEventUrl.isEmpty()) {
          continue;
        }
        eventUrls.add(BASE_URL + seasonEventUrl);
      }
    }

    // visit each event URL and extract event information
    var events = new ArrayList<GoMediaEvent>();
    for (var eventUrl : eventUrls) {
      var eventPage = fetchDocument(eventUrl);
      var event = parseEventPage(eventPage, eventUrl);
      events.add(event);
    }

    return events;
  }

  private GoMediaEvent parseEventPage(Document doc, String url) {
    var title = doc.select("h1.event-hero-carousel-heading").text();
    var info =
        doc.select(".event-summary li").stream()
            .map(Element::text)
            .collect(Collectors.joining(", "));

    // parse date from the hero carousel detail that contains the calendar icon
    var dateText =
        doc.select("span.event-hero-carousel-detail:has(svg[name=event-calendar])").text();
    var matcher = DATE_PATTERN.matcher(dateText);
    Verify.verify(matcher.find(), "Could not find date in text: %s", dateText);
    var date = LocalDate.parse(matcher.group(), DATE_FORMATTER);

    // find the latest time in the event info
    var timeMatcher = TIME_PATTERN.matcher(info);
    var times = new ArrayList<LocalTime>();
    while (timeMatcher.find()) {
      var timeStr = timeMatcher.group().trim();
      try {
        var time = LocalTime.parse(timeStr, TIME_FORMATTER);
        times.add(time);
      } catch (DateTimeParseException e) {
        // Skip invalid time formats and log the issue
        LOGGER.warn("Invalid time format found: '{}'. Error: {}", timeStr, e.getMessage());
      }
    }
    if (times.isEmpty()) {
      LOGGER.warn(
          "No valid times found in event info '{}' for URL: {}, using default time of 00:00",
          info,
          url);
    }
    var startTime = times.stream().max(LocalTime::compareTo).orElse(LocalTime.of(0, 0));
    var startDateTime = LocalDateTime.of(date, startTime);

    return new GoMediaEvent(
        title, STADIUM_URL, url, startDateTime.atZone(AUCKLAND_ZONE).toInstant(), info);
  }

  @VisibleForTesting
  protected Document fetchDocument(String url) throws IOException {
    return Jsoup.connect(url).get();
  }
}
