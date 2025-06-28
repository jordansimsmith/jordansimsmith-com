package com.jordansimsmith.eventcalendar;

import com.jordansimsmith.localstack.LocalStackContainer;

public class EventCalendarContainer extends LocalStackContainer<EventCalendarContainer> {
  public EventCalendarContainer() {
    super("test.properties", "eventcalendar.image.name", "eventcalendar.image.loader");
  }
}
