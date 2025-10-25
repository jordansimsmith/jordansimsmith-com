package com.jordansimsmith.eventcalendar;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeMeetupClient implements MeetupClient {
  private final Map<String, List<MeetupEvent>> eventsByGroup = new HashMap<>();

  @Override
  public List<MeetupEvent> getEvents(URI groupUrl) {
    return eventsByGroup.getOrDefault(groupUrl.toString(), List.of());
  }

  public void addEvent(URI groupUrl, MeetupEvent event) {
    eventsByGroup.computeIfAbsent(groupUrl.toString(), k -> new ArrayList<>()).add(event);
  }

  public void reset() {
    eventsByGroup.clear();
  }
}
