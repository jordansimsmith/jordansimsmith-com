package com.jordansimsmith.tcginventory.fetchtcg.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class HttpFetchTcgListingMutationTest {
  @Test
  void updateListingShouldSendProviderPayloadAndReturnListingState() throws Exception {
    // arrange
    var httpClient = mock(HttpClient.class);
    var response = response(200, "{\"listingId\":975737,\"remainingQuantity\":2}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);
    var pacerCalls = new AtomicInteger();
    var client =
        new HttpFetchTcgClient(
            URI.create("https://api.fetchtcg.example"),
            httpClient,
            new ObjectMapper(),
            pacerCalls::incrementAndGet);
    var request =
        new UpdateListingRequest(
            "mtg_218_c_kld_normal",
            "raw-nm",
            2,
            new BigDecimal("1.50"),
            "front.jpg",
            List.of("back.jpg"));

    // act
    var result = client.updateListing("private-bearer", request);

    // assert
    assertThat(result.listingId()).isEqualTo(975737);
    assertThat(result.remainingQuantity()).isEqualTo(2);
    assertThat(pacerCalls.get()).isEqualTo(1);
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    var httpRequest = requestCaptor.getValue();
    assertThat(httpRequest.uri())
        .isEqualTo(URI.create("https://api.fetchtcg.example/v2/private/manage-listings"));
    assertThat(httpRequest.method()).isEqualTo("POST");
    assertThat(httpRequest.headers().firstValue("Authorization")).hasValue("Bearer private-bearer");
    assertThat(httpRequest.headers().firstValue("Accept")).hasValue("application/json");
    assertThat(httpRequest.headers().firstValue("Content-Type")).hasValue("application/json");
    var body = new ObjectMapper().readTree(requestBody(httpRequest));
    assertThat(body.get("cardId").asText()).isEqualTo("mtg_218_c_kld_normal");
    assertThat(body.get("condition").asText()).isEqualTo("raw-nm");
    assertThat(body.get("listedPrice").decimalValue()).isEqualByComparingTo("1.50");
    assertThat(body.get("listedCurrency").asText()).isEqualTo("NZD");
    assertThat(body.get("matchPriceEnabled").asBoolean()).isFalse();
    assertThat(body.get("quantity").asInt()).isEqualTo(2);
    assertThat(body.get("details").asText()).isEmpty();
    assertThat(body.get("additionalImages").get(0).get("label").isNull()).isTrue();
    assertThat(body.get("additionalImages").get(0).get("url").asText()).isEqualTo("back.jpg");
  }

  @Test
  void updateListingShouldRetryServerFailuresAndPaceEveryAttempt() throws Exception {
    // arrange
    var httpClient = mock(HttpClient.class);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(
            response(503, "internal"),
            response(502, "internal"),
            response(500, "internal"),
            response(200, "{\"listingId\":1,\"remainingQuantity\":1}"));
    var pacerCalls = new AtomicInteger();
    var client =
        new HttpFetchTcgClient(
            URI.create("https://api.fetchtcg.example"),
            httpClient,
            new ObjectMapper(),
            pacerCalls::incrementAndGet);

    // act
    client.updateListing(
        "bearer", new UpdateListingRequest("card", "raw-nm", 1, BigDecimal.ONE, null, null));

    // assert
    verify(httpClient, times(4))
        .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(pacerCalls.get()).isEqualTo(4);
  }

  @Test
  void updateListingShouldFailVisiblyOnMalformedResponseWithoutProviderBody()
      throws IOException, InterruptedException {
    // arrange
    var httpClient = mock(HttpClient.class);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response(200, "provider-secret-body"));
    var client =
        new HttpFetchTcgClient(
            URI.create("https://api.fetchtcg.example"), httpClient, new ObjectMapper(), () -> {});

    // act / assert
    assertThatThrownBy(
            () ->
                client.updateListing(
                    "bearer",
                    new UpdateListingRequest("card", "raw-nm", 1, BigDecimal.ONE, null, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FetchTCG returned a malformed listing update response")
        .hasNoCause();
  }

  @Test
  void deleteListingShouldTreatMissingListingAsSuccess() throws Exception {
    // arrange
    var httpClient = mock(HttpClient.class);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response(404, "provider-secret-body"));
    var client =
        new HttpFetchTcgClient(
            URI.create("https://api.fetchtcg.example"), httpClient, new ObjectMapper(), () -> {});

    // act
    client.deleteListing("private-bearer", 975737);

    // assert
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requestCaptor.getValue().uri())
        .isEqualTo(URI.create("https://api.fetchtcg.example/v1/manage-listings/975737"));
    assertThat(requestCaptor.getValue().method()).isEqualTo("DELETE");
    assertThat(requestCaptor.getValue().headers().firstValue("Authorization"))
        .hasValue("Bearer private-bearer");
  }

  @Test
  void updateListingShouldPreserveNotFoundResponse() throws IOException, InterruptedException {
    // arrange
    var httpClient = mock(HttpClient.class);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response(404, "provider-secret-body"));
    var client =
        new HttpFetchTcgClient(
            URI.create("https://api.fetchtcg.example"), httpClient, new ObjectMapper(), () -> {});

    // act / assert
    assertThatThrownBy(
            () ->
                client.updateListing(
                    "bearer",
                    new UpdateListingRequest("card", "raw-nm", 1, BigDecimal.ONE, null, null)))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("FetchTCG card was not found");
  }

  @SuppressWarnings("unchecked")
  private static HttpResponse<String> response(int statusCode, String body) {
    var response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(statusCode);
    when(response.body()).thenReturn(body);
    return response;
  }

  private static String requestBody(HttpRequest request) {
    var publisher = request.bodyPublisher().orElseThrow();
    var subscriber = HttpResponse.BodySubscribers.ofByteArray();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscriber.onSubscribe(subscription);
          }

          @Override
          public void onNext(ByteBuffer item) {
            subscriber.onNext(List.of(item));
          }

          @Override
          public void onError(Throwable throwable) {
            subscriber.onError(throwable);
          }

          @Override
          public void onComplete() {
            subscriber.onComplete();
          }
        });
    return new String(subscriber.getBody().toCompletableFuture().join(), StandardCharsets.UTF_8);
  }
}
