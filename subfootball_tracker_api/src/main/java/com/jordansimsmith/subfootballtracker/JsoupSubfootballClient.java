package com.jordansimsmith.subfootballtracker;

import com.google.common.base.Preconditions;
import java.net.URI;
import org.jsoup.Jsoup;

public class JsoupSubfootballClient implements SubfootballClient {
  private final URI baseUri;

  public JsoupSubfootballClient(URI baseUri) {
    this.baseUri = Preconditions.checkNotNull(baseUri);
  }

  @Override
  public String getRegistrationContent() {
    try {
      return doGetRegistrationContent();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private String doGetRegistrationContent() throws Exception {
    var doc = Jsoup.connect(baseUri.resolve("/register").toString()).get();

    var content = doc.selectFirst(".page.content-item");
    Preconditions.checkNotNull(content);

    content.select("br").before("\\n");
    content.select("p").before("\\n");
    return content.text().replaceAll(" *\\\\n *", "\n").trim();
  }
}
