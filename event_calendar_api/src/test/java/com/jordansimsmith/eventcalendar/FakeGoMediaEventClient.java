package com.jordansimsmith.eventcalendar;

import java.util.ArrayList;
import java.util.List;

public class FakeGoMediaEventClient implements GoMediaEventClient {
  private List<GoMediaEvent> events = new ArrayList<>();

  @Override
  public List<GoMediaEvent> getEvents() {
    return events;
  }

  public void addEvent(GoMediaEvent event) {
    this.events.add(event);
  }

  public void reset() {
    events.clear();
  }
}
