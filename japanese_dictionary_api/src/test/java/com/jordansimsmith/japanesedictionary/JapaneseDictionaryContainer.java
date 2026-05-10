package com.jordansimsmith.japanesedictionary;

import com.jordansimsmith.localstack.LocalStackContainer;
import java.net.URI;

public class JapaneseDictionaryContainer extends LocalStackContainer<JapaneseDictionaryContainer> {
  public JapaneseDictionaryContainer() {
    super("test.properties", "japanesedictionary.image.name", "japanesedictionary.image.loader");
  }

  @SuppressWarnings("HttpUrlsUsage")
  public URI getApiUrl() {
    return URI.create(
        "http://%s:%d/_aws/execute-api/japanese_dictionary/local"
            .formatted(this.getHost(), this.getLocalstackPort()));
  }
}
