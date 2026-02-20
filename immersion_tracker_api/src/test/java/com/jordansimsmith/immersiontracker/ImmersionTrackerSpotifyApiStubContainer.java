package com.jordansimsmith.immersiontracker;

import com.jordansimsmith.http.HttpStubContainer;

public class ImmersionTrackerSpotifyApiStubContainer
    extends HttpStubContainer<ImmersionTrackerSpotifyApiStubContainer> {
  public ImmersionTrackerSpotifyApiStubContainer() {
    super(
        "test.properties",
        "immersiontrackerspotifyapistub.image.name",
        "immersiontrackerspotifyapistub.image.loader",
        "/opt/code/immersion-tracker-spotify-api-stub/immersion-tracker-spotify-api-stub-server_deploy.jar",
        "com.jordansimsmith.immersiontracker.ImmersionTrackerSpotifyApiStubServer",
        "/health");
  }
}
