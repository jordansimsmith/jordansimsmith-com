package com.jordansimsmith.footballcalendar;

import com.jordansimsmith.http.HttpStubContainer;

public class CometMockContainer extends HttpStubContainer<CometMockContainer> {
  public CometMockContainer() {
    super(
        "test.properties",
        "footballcalendarcometmock.image.name",
        "footballcalendarcometmock.image.loader",
        "/opt/code/comet-mock/comet-mock-server_deploy.jar",
        "com.jordansimsmith.footballcalendar.CometMockServer",
        "/health");
  }
}
