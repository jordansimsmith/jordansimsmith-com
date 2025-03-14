package com.jordansimsmith.eventcalendar;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.List;
import org.jsoup.nodes.Document;

public class JsoupGoMediaEventClient implements GoMediaEventClient {
  @Override
  public List<GoMediaEvent> getEvents() {
    try {
      return doGetEvents();
    } catch (Exception e) {
      throw new RuntimeException("Failed to get events", e);
    }
  }

  private List<GoMediaEvent> doGetEvents() throws Exception {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  @VisibleForTesting
  protected Document fetchDocument(String url) throws IOException {
    throw new UnsupportedOperationException("Not yet implemented");
  }
}
