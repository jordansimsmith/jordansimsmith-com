package com.jordansimsmith.tcginventory.catalog;

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

public class HttpTcgCsvClientTest {
  @Test
  void requestShouldRetryTransportFailureAndPaceEveryAttempt()
      throws IOException, InterruptedException {
    // arrange
    var httpClient = mock(HttpClient.class);
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("2026-10-07T20:06:09+0000");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenThrow(new IOException("connection reset"))
        .thenReturn(response);
    var pacedRequests = new AtomicInteger();
    var client =
        new HttpTcgCsvClient(
            URI.create("https://tcgcsv.example"),
            httpClient,
            new ObjectMapper(),
            pacedRequests::incrementAndGet);

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
    var httpClient = mock(HttpClient.class);
    var failure = new IOException("connection reset");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenThrow(failure)
        .thenThrow(failure)
        .thenThrow(failure)
        .thenThrow(failure);
    var pacedRequests = new AtomicInteger();
    var client =
        new HttpTcgCsvClient(
            URI.create("https://tcgcsv.example"),
            httpClient,
            new ObjectMapper(),
            pacedRequests::incrementAndGet);

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
    var httpClient = mock(HttpClient.class);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenThrow(new InterruptedException("interrupted"));
    var pacedRequests = new AtomicInteger();
    var client =
        new HttpTcgCsvClient(
            URI.create("https://tcgcsv.example"),
            httpClient,
            new ObjectMapper(),
            pacedRequests::incrementAndGet);

    // act / assert
    assertThatThrownBy(client::getUpdateMarker).isInstanceOf(InterruptedException.class);
    assertThat(pacedRequests).hasValue(1);
    verify(httpClient, times(1))
        .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
  }
}
