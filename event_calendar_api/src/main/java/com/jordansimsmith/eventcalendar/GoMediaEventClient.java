package com.jordansimsmith.eventcalendar;

import java.time.Instant;
import java.util.List;

public interface GoMediaEventClient {
  String BASE_URL = "https://www.aucklandstadiums.co.nz";
  String STADIUM_URL = BASE_URL + "/our-venues/go-media-stadium";

  record GoMediaEvent(
      String title, String stadiumUrl, String eventUrl, Instant startTime, String eventInfo) {}

  /**
   * Get all events from the Go Media Stadium website.
   *
   * @return A list of events with their details
   */
  List<GoMediaEvent> findEvents();
}
