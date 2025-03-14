package com.jordansimsmith.eventcalendar;

import java.util.List;

public interface GoMediaEventClient {
  /**
   * Get all events from the Go Media Stadium website.
   *
   * @return A list of events with their details
   */
  List<GoMediaEvent> getEvents();
}
