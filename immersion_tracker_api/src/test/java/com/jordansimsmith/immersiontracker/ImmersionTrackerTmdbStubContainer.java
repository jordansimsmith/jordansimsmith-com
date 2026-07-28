package com.jordansimsmith.immersiontracker;

import com.jordansimsmith.http.HttpStubContainer;

public class ImmersionTrackerTmdbStubContainer
    extends HttpStubContainer<ImmersionTrackerTmdbStubContainer> {
  public ImmersionTrackerTmdbStubContainer() {
    super(
        "test.properties",
        "immersiontrackertmdbstub.image.name",
        "immersiontrackertmdbstub.image.loader",
        "/opt/code/immersion-tracker-tmdb-stub/immersion-tracker-tmdb-stub-server_deploy.jar",
        "com.jordansimsmith.immersiontracker.ImmersionTrackerTmdbStubServer",
        "/health",
        "tmdb-stub");
  }
}
