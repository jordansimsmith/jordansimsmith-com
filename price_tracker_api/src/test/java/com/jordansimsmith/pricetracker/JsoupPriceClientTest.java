package com.jordansimsmith.pricetracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.Connection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class JsoupPriceClientTest {
  @Mock PriceExtractor mockExtractor;

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

    jsoupPriceClient = new JsoupPriceClient(extractors);
  }

  @Test
  void shouldThrowExceptionForUnsupportedWebsite() {
    // arrange
    var unsupportedUrl = URI.create("https://unsupported-website.com/product");

    // act & assert
    assertThatThrownBy(() -> jsoupPriceClient.getPrice(unsupportedUrl))
        .isInstanceOf(Exception.class);
  }

  @Test
  void getPriceShouldFailAfterOneRequestWhenResponseIsRateLimited() {
    // arrange
    var attempts = new AtomicInteger();
    var response = mock(Connection.Response.class);
    when(response.statusCode()).thenReturn(429);
    when(response.headers()).thenReturn(Map.of());
    when(response.body()).thenReturn("");
    var client =
        new JsoupPriceClient(Map.of("testdomain.com", mockExtractor)) {
          @Override
          protected Connection.Response fetchResponse(String url) {
            attempts.incrementAndGet();
            return response;
          }
        };

    // act & assert
    assertThatThrownBy(() -> client.getPrice(URI.create("https://testdomain.com/product")))
        .isInstanceOf(RuntimeException.class);
    assertThat(attempts).hasValue(1);
  }
}
