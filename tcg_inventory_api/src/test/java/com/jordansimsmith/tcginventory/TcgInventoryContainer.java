package com.jordansimsmith.tcginventory;

import com.jordansimsmith.localstack.LocalStackContainer;
import java.net.URI;
import java.time.Duration;

public class TcgInventoryContainer extends LocalStackContainer<TcgInventoryContainer> {
  public TcgInventoryContainer() {
    super("test.properties", "tcginventory.image.name", "tcginventory.image.loader");
    this.withStartupTimeout(Duration.ofMinutes(5));
  }

  @SuppressWarnings("HttpUrlsUsage")
  public URI getApiUrl() {
    return URI.create(
        "http://%s:%d/restapis/tcg_inventory/local/_user_request_"
            .formatted(this.getHost(), this.getLocalstackPort()));
  }
}
