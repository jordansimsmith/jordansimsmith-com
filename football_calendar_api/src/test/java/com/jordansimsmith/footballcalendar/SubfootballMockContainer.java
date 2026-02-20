package com.jordansimsmith.footballcalendar;

import com.jordansimsmith.http.HttpStubContainer;

public class SubfootballMockContainer extends HttpStubContainer<SubfootballMockContainer> {
  public SubfootballMockContainer() {
    super(
        "test.properties",
        "footballcalendarsubfootballmock.image.name",
        "footballcalendarsubfootballmock.image.loader",
        "/opt/code/subfootball-mock/subfootball-mock-server_deploy.jar",
        "com.jordansimsmith.footballcalendar.SubfootballMockServer",
        "/health");
  }
}
