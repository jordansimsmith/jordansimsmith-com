package com.jordansimsmith.eventcalendar;

import java.net.URI;
import java.time.Instant;
import java.util.List;

public interface MeetupClient {
  record MeetupEvent(
      String title, String groupUrl, String eventUrl, Instant startTime, String location) {}

  List<MeetupEvent> findEvents(URI groupUrl);
}
