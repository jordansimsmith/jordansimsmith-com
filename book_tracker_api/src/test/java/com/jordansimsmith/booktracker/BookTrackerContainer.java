package com.jordansimsmith.booktracker;

import com.jordansimsmith.localstack.LocalStackContainer;
import java.net.URI;

public class BookTrackerContainer extends LocalStackContainer<BookTrackerContainer> {
  public BookTrackerContainer() {
    super("test.properties", "booktracker.image.name", "booktracker.image.loader");
  }

  @SuppressWarnings("HttpUrlsUsage")
  public URI getApiUrl() {
    return URI.create(
        "http://%s:%d/restapis/book_tracker/local/_user_request_"
            .formatted(this.getHost(), this.getLocalstackPort()));
  }
}
