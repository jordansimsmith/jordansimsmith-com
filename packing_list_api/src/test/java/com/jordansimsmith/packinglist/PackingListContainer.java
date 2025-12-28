package com.jordansimsmith.packinglist;

import com.jordansimsmith.localstack.LocalStackContainer;
import java.net.URI;

public class PackingListContainer extends LocalStackContainer<PackingListContainer> {
  public PackingListContainer() {
    super("test.properties", "packinglist.image.name", "packinglist.image.loader");
  }

  @SuppressWarnings("HttpUrlsUsage")
  public URI getApiUrl() {
    return URI.create(
        "http://%s:%d/restapis/packing_list/local/_user_request_"
            .formatted(this.getHost(), this.getLocalstackPort()));
  }
}
