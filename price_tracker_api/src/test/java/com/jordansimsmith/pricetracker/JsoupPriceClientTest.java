package com.jordansimsmith.pricetracker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class JsoupPriceClientTest {
  @Mock PriceExtractor mockExtractor;
  @Mock RandomGenerator mockRandom;

  private JsoupPriceClient jsoupPriceClient;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);

    var extractors =
        Map.of(
            "testdomain.com",
            mockExtractor,
            "chemistwarehouse",
            mockExtractor,
            "nzprotein",
            mockExtractor);

    jsoupPriceClient = new JsoupPriceClient(mockRandom, extractors);
  }

  @Test
  void shouldThrowExceptionForUnsupportedWebsite() {
    // arrange
    var unsupportedUrl = URI.create("https://unsupported-website.com/product");

    // act & assert
    assertThatThrownBy(() -> jsoupPriceClient.getPrice(unsupportedUrl))
        .isInstanceOf(Exception.class);
  }
}
