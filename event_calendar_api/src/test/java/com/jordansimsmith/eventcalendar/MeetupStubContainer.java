package com.jordansimsmith.eventcalendar;

import com.jordansimsmith.http.HttpStubContainer;

public class MeetupStubContainer extends HttpStubContainer<MeetupStubContainer> {
  public MeetupStubContainer() {
    super(
        "test.properties",
        "eventcalendarmeetupstub.image.name",
        "eventcalendarmeetupstub.image.loader",
        "/opt/code/meetup-stub/meetup-stub-server_deploy.jar",
        "com.jordansimsmith.eventcalendar.MeetupStubServer",
        "/health");
  }
}
