package com.jordansimsmith.footballcalendar;

import com.jordansimsmith.http.HttpStubContainer;

public class NrfMockContainer extends HttpStubContainer<NrfMockContainer> {
  public NrfMockContainer() {
    super(
        "test.properties",
        "footballcalendarnrfmock.image.name",
        "footballcalendarnrfmock.image.loader",
        "/opt/code/nrf-mock/nrf-mock-server_deploy.jar",
        "com.jordansimsmith.footballcalendar.NrfMockServer",
        "/health");
  }
}
