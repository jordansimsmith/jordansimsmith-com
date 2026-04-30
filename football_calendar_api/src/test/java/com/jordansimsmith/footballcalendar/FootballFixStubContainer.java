package com.jordansimsmith.footballcalendar;

import com.jordansimsmith.http.HttpStubContainer;

public class FootballFixStubContainer extends HttpStubContainer<FootballFixStubContainer> {
  public FootballFixStubContainer() {
    super(
        "test.properties",
        "footballcalendarfootballfixstub.image.name",
        "footballcalendarfootballfixstub.image.loader",
        "/opt/code/football-fix-stub/football-fix-stub-server_deploy.jar",
        "com.jordansimsmith.footballcalendar.FootballFixStubServer",
        "/health");
  }
}
