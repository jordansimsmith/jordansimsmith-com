package com.jordansimsmith.tcginventory.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class HttpTcgCsvClientTest {
  private final HttpClient httpClient = mock(HttpClient.class);
  private final AtomicInteger pacedRequests = new AtomicInteger();
  private final HttpTcgCsvClient client =
      new HttpTcgCsvClient(
          URI.create("https://tcgcsv.example"),
          httpClient,
          new ObjectMapper(),
          pacedRequests::incrementAndGet);

  @Test
  void requestShouldRetryTransportFailureAndPaceEveryAttempt()
      throws IOException, InterruptedException {
    // arrange
    var markerResponse = response(200, "2026-10-07T20:06:09+0000");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenThrow(new IOException("connection reset"))
        .thenReturn(markerResponse);

    // act
    var marker = client.getUpdateMarker();

    // assert
    assertThat(marker.updatedAt()).isEqualTo(Instant.parse("2026-10-07T20:06:09Z"));
    assertThat(pacedRequests).hasValue(2);
    verify(httpClient, times(2))
        .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void requestShouldFailAfterFourTransportFailures() throws IOException, InterruptedException {
    // arrange
    var failure = new IOException("connection reset");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenThrow(failure)
        .thenThrow(failure)
        .thenThrow(failure)
        .thenThrow(failure);

    // act / assert
    assertThatThrownBy(client::getUpdateMarker)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("after 4 attempts")
        .hasMessageContaining("/last-updated.txt")
        .hasCause(failure);
    assertThat(pacedRequests).hasValue(4);
    verify(httpClient, times(4))
        .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void requestShouldPropagateInterruptionWithoutRetry() throws IOException, InterruptedException {
    // arrange
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenThrow(new InterruptedException("interrupted"));

    // act / assert
    assertThatThrownBy(client::getUpdateMarker).isInstanceOf(InterruptedException.class);
    assertThat(pacedRequests).hasValue(1);
    verify(httpClient, times(1))
        .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void requestsShouldUseTcgCsvPathsAndHeaders() throws IOException, InterruptedException {
    // arrange
    var markerResponse = response(200, "2026-10-07T20:06:09+0000");
    var groupsResponse =
        response(
            200,
            """
            {"success":true,"errors":[],"totalItems":1,"results":[
              {"groupId":3170,"categoryId":3,"name":"Silver Tempest","abbreviation":"SWSH12"}]}
            """);
    var productsResponse =
        response(
            200,
            """
            {"success":true,"errors":[],"totalItems":1,"results":[
              {"productId":10,"categoryId":3,"groupId":3170,"name":"Candice","extendedData":[]}]}
            """);
    var pricesResponse =
        response(
            200,
            """
            {"success":true,"errors":[],"totalItems":1,"results":[
              {"productId":10,"subTypeName":"Normal","marketPrice":null}]}
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(markerResponse)
        .thenReturn(groupsResponse)
        .thenReturn(productsResponse)
        .thenReturn(pricesResponse);

    // act
    var marker = client.getUpdateMarker();
    var groups = client.findGroups(3);
    var products = client.findProducts(3, 3170);
    var prices = client.findPrices(3, 3170);

    // assert
    assertThat(marker.sourceMarker()).isEqualTo("2026-10-07T20:06:09+0000");
    assertThat(marker.updatedAt()).isEqualTo(Instant.parse("2026-10-07T20:06:09Z"));
    assertThat(groups).singleElement().extracting(TcgCsvClient.Group::groupId).isEqualTo(3170);
    assertThat(products).singleElement().extracting(TcgCsvClient.Product::productId).isEqualTo(10);
    assertThat(prices).containsExactly(new TcgCsvClient.Price(10, "Normal"));
    var requests = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(4)).send(requests.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requests.getAllValues())
        .extracting(request -> request.uri().getPath())
        .containsExactly(
            "/last-updated.txt",
            "/tcgplayer/3/groups",
            "/tcgplayer/3/3170/products",
            "/tcgplayer/3/3170/prices");
    assertThat(requests.getAllValues())
        .allSatisfy(
            request ->
                assertThat(request.headers().firstValue("User-Agent"))
                    .contains("TcgInventory/1.0 (https://tcg-inventory.jordansimsmith.com)"));
    assertThat(pacedRequests).hasValue(4);
  }

  @Test
  void findProductsShouldAcceptMissingNullAndEmptyExtendedData()
      throws IOException, InterruptedException {
    // arrange
    var productsResponse =
        response(
            200,
            """
            {"success":true,"errors":[],"totalItems":3,"results":[
              {"productId":10,"categoryId":3,"groupId":3170,"name":"Booster Box"},
              {"productId":11,"categoryId":3,"groupId":3170,"name":"Sleeves","extendedData":null},
              {"productId":12,"categoryId":3,"groupId":3170,"name":"Token","extendedData":[]}]}
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(productsResponse);

    // act
    var products = client.findProducts(3, 3170);

    // assert
    assertThat(products).hasSize(3);
    assertThat(products.get(0).extendedData()).isNull();
    assertThat(products.get(1).extendedData()).isNull();
    assertThat(products.get(2).extendedData()).isEmpty();
  }

  @Test
  void collectionShouldRejectInvalidResponses() throws IOException, InterruptedException {
    // arrange
    var unsuccessful =
        response(
            200,
            """
            {"success":false,"errors":["bad"],"totalItems":0,"results":[]}
            """);
    var wrongCount =
        response(
            200,
            """
            {"success":true,"errors":[],"totalItems":2,"results":[]}
            """);
    var malformed = response(200, "{not-json");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(unsuccessful)
        .thenReturn(wrongCount)
        .thenReturn(malformed);

    // act / assert
    assertThatThrownBy(() -> client.findGroups(3))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("unsuccessful collection");
    assertThatThrownBy(() -> client.findGroups(3))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("count does not match");
    assertThatThrownBy(() -> client.findGroups(3)).isInstanceOf(IOException.class);
    assertThat(pacedRequests).hasValue(3);
  }

  @Test
  void requestShouldRejectClientErrorsWithoutRetry() throws IOException, InterruptedException {
    // arrange
    var rateLimited = response(429, "");
    var notFound = response(404, "");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(rateLimited)
        .thenReturn(notFound);

    // act / assert
    assertThatThrownBy(() -> client.findGroups(3))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("status code 429");
    assertThat(pacedRequests).hasValue(1);
    assertThatThrownBy(() -> client.findGroups(3))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("status code 404");
    assertThat(pacedRequests).hasValue(2);
  }

  @Test
  void getUpdateMarkerShouldRejectMalformedTimestamp() throws IOException, InterruptedException {
    // arrange
    var malformed = response(200, "yesterday");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(malformed);

    // act / assert
    assertThatThrownBy(client::getUpdateMarker)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("not an offset timestamp");
  }

  @Test
  void requestShouldRetryServerErrorsAndPaceEveryAttempt()
      throws IOException, InterruptedException {
    // arrange
    var unavailable = response(503, "");
    var serverError = response(500, "");
    var markerResponse = response(200, "2026-10-07T20:06:09+0000");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(unavailable)
        .thenReturn(serverError)
        .thenReturn(markerResponse);

    // act
    var marker = client.getUpdateMarker();

    // assert
    assertThat(marker.updatedAt()).isEqualTo(Instant.parse("2026-10-07T20:06:09Z"));
    assertThat(pacedRequests).hasValue(3);
  }

  @Test
  void requestShouldFailAfterFourServerErrors() throws IOException, InterruptedException {
    // arrange
    var unavailable = response(503, "");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(unavailable);

    // act / assert
    assertThatThrownBy(client::getUpdateMarker)
        .isInstanceOf(IOException.class)
        .hasMessageContaining("after 4 attempt(s)")
        .hasMessageContaining("/last-updated.txt");
    assertThat(pacedRequests).hasValue(4);
  }

  private HttpResponse<String> response(int status, String body) {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    return response;
  }
}
