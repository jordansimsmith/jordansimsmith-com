package com.jordansimsmith.footballcalendar;

import com.jordansimsmith.http.HttpStubContainer;

public class SubfootballStubContainer extends HttpStubContainer<SubfootballStubContainer> {
  public SubfootballStubContainer() {
    super(
        "test.properties",
        "footballcalendarsubfootballstub.image.name",
        "footballcalendarsubfootballstub.image.loader",
        "/opt/code/subfootball-stub/subfootball-stub-server_deploy.jar",
        "com.jordansimsmith.footballcalendar.SubfootballStubServer",
        "/health",
        "subfootball-stub");
  }
}
