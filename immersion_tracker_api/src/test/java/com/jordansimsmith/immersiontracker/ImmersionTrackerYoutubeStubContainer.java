package com.jordansimsmith.immersiontracker;

import com.jordansimsmith.http.HttpStubContainer;

public class ImmersionTrackerYoutubeStubContainer
    extends HttpStubContainer<ImmersionTrackerYoutubeStubContainer> {
  public ImmersionTrackerYoutubeStubContainer() {
    super(
        "test.properties",
        "immersiontrackeryoutubestub.image.name",
        "immersiontrackeryoutubestub.image.loader",
        "/opt/code/immersion-tracker-youtube-stub/immersion-tracker-youtube-stub-server_deploy.jar",
        "com.jordansimsmith.immersiontracker.ImmersionTrackerYoutubeStubServer",
        "/health");
  }
}
