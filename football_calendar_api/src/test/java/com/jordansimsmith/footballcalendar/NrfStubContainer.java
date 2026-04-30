package com.jordansimsmith.footballcalendar;

import com.jordansimsmith.http.HttpStubContainer;

public class NrfStubContainer extends HttpStubContainer<NrfStubContainer> {
  public NrfStubContainer() {
    super(
        "test.properties",
        "footballcalendarnrfstub.image.name",
        "footballcalendarnrfstub.image.loader",
        "/opt/code/nrf-stub/nrf-stub-server_deploy.jar",
        "com.jordansimsmith.footballcalendar.NrfStubServer",
        "/health",
        "nrf-stub");
  }
}
