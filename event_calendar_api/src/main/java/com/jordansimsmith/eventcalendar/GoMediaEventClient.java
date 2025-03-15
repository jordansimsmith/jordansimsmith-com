package com.jordansimsmith.eventcalendar;

import java.time.Instant;
import java.util.List;

public interface GoMediaEventClient {
  record GoMediaEvent(
      String title, String stadiumUrl, String eventUrl, Instant startTime, String eventInfo) {}

  /**
   * Get all events from the Go Media Stadium website.
   *
   * @return A list of events with their details
   */
  List<GoMediaEvent> getEvents();
}
