package com.jordansimsmith.tcginventory.fetchtcg.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class HttpFetchTcgPricingLookupTest {
  private final HttpClient httpClient = mock(HttpClient.class);
  private final AtomicInteger pacerCalls = new AtomicInteger();
  private final HttpFetchTcgClient client =
      new HttpFetchTcgClient(
          URI.create("https://api.fetchtcg.example"),
          httpClient,
          new ObjectMapper(),
          pacerCalls::incrementAndGet);

  @AfterEach
  void tearDown() {
    Thread.interrupted();
  }

  @Test
  void getCardShouldReturnUnroundedNzMarketPriceAndEncodePath()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var response =
        createResponse(
            200,
            """
            {"id":"set/one? two","name":"Card","pricingData":{"NZ":{"tcgMarketPrice":1.257},"US":{"tcgMarketPrice":9}},"externalReferences":{"scryfallId":"abc"},"extra":true}
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    var result = client.getCard("set/one? two");

    // assert
    assertThat(result.id()).isEqualTo("set/one? two");
    assertThat(result.name()).isEqualTo("Card");
    assertThat(result.marketPrice()).isEqualByComparingTo("1.257");
    assertThat(result.externalReferences()).containsEntry("scryfallId", "abc");
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requestCaptor.getValue().uri())
        .isEqualTo(URI.create("https://api.fetchtcg.example/v3/cards/set%2Fone%3F%20two"));
    assertThat(requestCaptor.getValue().headers().firstValue("Accept"))
        .contains("application/json");
    assertThat(requestCaptor.getValue().headers().firstValue("Authorization")).isEmpty();
    assertThat(pacerCalls.get()).isEqualTo(1);
  }

  @Test
  void getCardShouldPreserveAbsentMarketPriceAndReferences()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var response = createResponse(200, "{\"id\":\"card\",\"name\":\"Card\"}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    var result = client.getCard("card");

    // assert
    assertThat(result.marketPrice()).isNull();
    assertThat(result.externalReferences()).isNull();
  }

  @Test
  void getCardShouldRejectMismatchedAndMalformedPayloadsWithoutBodyDiagnostics()
      throws IOException, InterruptedException {
    for (var body :
        new String[] {
          "{\"id\":\"other\",\"name\":\"Card\"}",
          "provider-body-with-private-data",
          "{\"id\":\"card\",\"pricingData\":{\"NZ\":{\"tcgMarketPrice\":\"bad\"}}}"
        }) {
      // arrange
      var response = createResponse(200, body);
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(response);

      // act
      var thrown = catchThrowable(() -> client.getCard("card"));

      // assert
      assertThat(thrown)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining("provider-body-with-private-data")
          .hasMessageNotContaining("bad");
      assertThat(thrown.getCause()).isNull();
    }
  }

  @Test
  void findCardListingsShouldRequestSpecifiedPageAndMapRequestedCurrency()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var response =
        createResponse(
            200,
            """
            {"searchResults":{"content":[{"id":42,"condition":"raw-nm","listedPrice":3.98,"listedCurrency":"AUD","listedPriceInRequestedCurrency":5.25,"requestedCurrency":"NZD","sellerProfileName":"seller"}],"number":1,"size":2,"totalElements":3,"totalPages":2,"last":true}}
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    var result = client.findCardListings("card", 2, 1);

    // assert
    assertThat(result.number()).isEqualTo(1);
    assertThat(result.size()).isEqualTo(2);
    assertThat(result.last()).isTrue();
    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst().listedPriceInRequestedCurrency())
        .isEqualByComparingTo("5.25");
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    var request = requestCaptor.getValue();
    assertThat(request.uri())
        .isEqualTo(
            URI.create(
                "https://api.fetchtcg.example/v3/cards/card/listings?countryCode=NZ&currencyCode=NZD&pageSize=2&pageOffset=1&sort=PRICE_ASC"));
    assertThat(request.headers().firstValue("Authorization")).isEmpty();
    assertThat(request.headers().firstValue("Accept")).contains("application/json");
    assertThat(pacerCalls.get()).isEqualTo(1);
  }

  @Test
  void findCardListingsShouldRetryServerErrorsAndFailOnInvalidMetadata()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var unavailable = createResponse(503, "private provider body");
    var success =
        createResponse(
            200, "{\"searchResults\":{\"content\":[],\"number\":0,\"size\":2,\"last\":true}}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(unavailable, success);

    // act
    var result = client.findCardListings("card", 2, 0);

    // assert
    assertThat(result.content()).isEmpty();
    verify(httpClient, times(2))
        .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(pacerCalls.get()).isEqualTo(2);

    var malformed = createResponse(200, "{\"searchResults\":{\"content\":[]}}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(malformed);
    var incomplete = client.findCardListings("card", 2, 0);
    assertThat(incomplete.number()).isNull();
    assertThat(incomplete.size()).isNull();
    assertThat(incomplete.last()).isNull();
  }

  @Test
  void findCardListingsShouldMapNotFoundAndOtherFailuresWithoutRetry()
      throws IOException, InterruptedException {
    // arrange
    var notFound = createResponse(404, "private body");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(notFound);

    // act
    var missing = catchThrowable(() -> client.findCardListings("card", 2, 0));

    // assert
    assertThat(missing)
        .isInstanceOf(NotFoundException.class)
        .hasMessage("FetchTCG card was not found");
    verify(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));

    var rateLimited = createResponse(429, "private body");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(rateLimited);
    var unavailable = catchThrowable(() -> client.findCardListings("card", 2, 0));
    assertThat(unavailable)
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageNotContaining("private body");
  }

  @Test
  void publicReadsShouldRepresentAuthenticationFailuresWithoutBody()
      throws IOException, InterruptedException {
    for (var status : new int[] {401, 403}) {
      // arrange
      var response = createResponse(status, "private-response-bearer-secret");
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(response);

      // act
      var thrown = catchThrowable(() -> client.getCard("card"));

      // assert
      assertThat(thrown)
          .isInstanceOf(FetchTcgAuthenticationException.class)
          .hasMessage("FetchTCG authentication failed with status " + status)
          .hasMessageNotContaining("private-response")
          .hasMessageNotContaining("bearer-secret");
    }
  }

  @Test
  void findCardListingsShouldHideNetworkDetailsAndRestoreInterruption()
      throws IOException, InterruptedException {
    // arrange
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenThrow(new IOException("private body"));

    // act
    var networkFailure = catchThrowable(() -> client.findCardListings("card", 2, 0));

    // assert
    assertThat(networkFailure)
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageNotContaining("private body");
    assertThat(networkFailure.getCause()).isNull();

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenThrow(new InterruptedException("private body"));
    var interrupted = catchThrowable(() -> client.findCardListings("card", 2, 0));
    assertThat(interrupted)
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageNotContaining("private body");
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  @Test
  void findCardListingsShouldFailAfterBoundedRetries() throws IOException, InterruptedException {
    // arrange
    var unavailable = createResponse(503, "private body");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(unavailable);

    // act
    var thrown = catchThrowable(() -> client.findCardListings("card", 2, 0));

    // assert
    assertThat(thrown)
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageNotContaining("private body");
    verify(httpClient, times(4))
        .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(pacerCalls.get()).isEqualTo(4);
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> createResponse(int statusCode, String body) {
    var response = (HttpResponse<String>) mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(statusCode);
    when(response.body()).thenReturn(body);
    return response;
  }
}
