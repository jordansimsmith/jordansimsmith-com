package com.jordansimsmith.eventcalendar;

import com.jordansimsmith.http.HttpStubContainer;

public class GoMediaStubContainer extends HttpStubContainer<GoMediaStubContainer> {
  public GoMediaStubContainer() {
    super(
        "test.properties",
        "eventcalendargomediastub.image.name",
        "eventcalendargomediastub.image.loader",
        "/opt/code/go-media-stub/go-media-stub-server_deploy.jar",
        "com.jordansimsmith.eventcalendar.GoMediaStubServer",
        "/health");
  }
}
