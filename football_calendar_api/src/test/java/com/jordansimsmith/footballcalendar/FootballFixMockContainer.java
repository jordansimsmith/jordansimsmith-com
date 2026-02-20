package com.jordansimsmith.footballcalendar;

import com.jordansimsmith.http.HttpStubContainer;

public class FootballFixMockContainer extends HttpStubContainer<FootballFixMockContainer> {
  public FootballFixMockContainer() {
    super(
        "test.properties",
        "footballcalendarfootballfixmock.image.name",
        "footballcalendarfootballfixmock.image.loader",
        "/opt/code/football-fix-mock/football-fix-mock-server_deploy.jar",
        "com.jordansimsmith.footballcalendar.FootballFixMockServer",
        "/health");
  }
}
