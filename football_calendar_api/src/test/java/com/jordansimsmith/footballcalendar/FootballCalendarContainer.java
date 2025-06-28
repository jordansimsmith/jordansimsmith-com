package com.jordansimsmith.footballcalendar;

import com.jordansimsmith.localstack.LocalStackContainer;

public class FootballCalendarContainer extends LocalStackContainer<FootballCalendarContainer> {
  public FootballCalendarContainer() {
    super("test.properties", "footballcalendar.image.name", "footballcalendar.image.loader");
  }
}
