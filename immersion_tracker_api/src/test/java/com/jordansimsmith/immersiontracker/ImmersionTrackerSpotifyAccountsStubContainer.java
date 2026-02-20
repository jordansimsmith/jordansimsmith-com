package com.jordansimsmith.immersiontracker;

import com.jordansimsmith.http.HttpStubContainer;

public class ImmersionTrackerSpotifyAccountsStubContainer
    extends HttpStubContainer<ImmersionTrackerSpotifyAccountsStubContainer> {
  public ImmersionTrackerSpotifyAccountsStubContainer() {
    super(
        "test.properties",
        "immersiontrackerspotifyaccountsstub.image.name",
        "immersiontrackerspotifyaccountsstub.image.loader",
        "/opt/code/immersion-tracker-spotify-accounts-stub/immersion-tracker-spotify-accounts-stub-server_deploy.jar",
        "com.jordansimsmith.immersiontracker.ImmersionTrackerSpotifyAccountsStubServer",
        "/health");
  }
}
