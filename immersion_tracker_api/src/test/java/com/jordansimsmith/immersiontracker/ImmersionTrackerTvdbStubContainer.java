package com.jordansimsmith.immersiontracker;

import com.jordansimsmith.http.HttpStubContainer;

public class ImmersionTrackerTvdbStubContainer
    extends HttpStubContainer<ImmersionTrackerTvdbStubContainer> {
  public ImmersionTrackerTvdbStubContainer() {
    super(
        "test.properties",
        "immersiontrackertvdbstub.image.name",
        "immersiontrackertvdbstub.image.loader",
        "/opt/code/immersion-tracker-tvdb-stub/immersion-tracker-tvdb-stub-server_deploy.jar",
        "com.jordansimsmith.immersiontracker.ImmersionTrackerTvdbStubServer",
        "/health");
  }
}
