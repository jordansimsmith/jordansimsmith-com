package com.jordansimsmith.japanesedictionary;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

public class DictionaryFactoryTest {
  @Test
  void createShouldProvideCoreBindings() {
    var factory = DictionaryTestFactory.create(URI.create("unused"));

    assertThat(factory.objectMapper()).isNotNull();
    assertThat(factory.secrets()).isNotNull();
    assertThat(factory.requestContextFactory()).isNotNull();
    assertThat(factory.httpResponseFactory()).isNotNull();
    assertThat(factory.fakeSecrets()).isNotNull();
  }
}
