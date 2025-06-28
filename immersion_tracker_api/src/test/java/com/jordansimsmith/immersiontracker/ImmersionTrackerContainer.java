package com.jordansimsmith.immersiontracker;

import com.jordansimsmith.localstack.LocalStackContainer;
import java.net.URI;

public class ImmersionTrackerContainer extends LocalStackContainer<ImmersionTrackerContainer> {
  public ImmersionTrackerContainer() {
    super("test.properties", "immersiontracker.image.name", "immersiontracker.image.loader");
    this.withEnv("TVDB_API_KEY", System.getenv("TVDB_API_KEY"));
  }

  @SuppressWarnings("HttpUrlsUsage")
  public URI getApiUrl() {
    return URI.create(
        "http://%s:%d/restapis/immersion_tracker/local/_user_request_"
            .formatted(this.getHost(), this.getLocalstackPort()));
  }
}
