package com.jordansimsmith.tcginventory.fetchtcg.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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

public class HttpFetchTcgClientTest {
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
  void getProfileShouldSendBearerAndReturnExactUsername()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var httpResponse = createResponse(200, "{\"username\":\" Seller Name \",\"extra\":1}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(httpResponse);

    // act
    var response = client.getProfile("bearer-secret");

    // assert
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    var request = requestCaptor.getValue();
    assertThat(request.uri())
        .isEqualTo(URI.create("https://api.fetchtcg.example/v1/private/profile"));
    assertThat(request.headers().firstValue("User-Agent"))
        .hasValueSatisfying(
            userAgent ->
                assertThat(userAgent).startsWith("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"));
    assertThat(request.headers().firstValue("Accept")).contains("application/json");
    assertThat(request.headers().firstValue("Authorization")).contains("Bearer bearer-secret");
    assertThat(response.username()).isEqualTo(" Seller Name ");
    assertThat(pacerCalls.get()).isEqualTo(1);
  }

  @Test
  void getProfileShouldRetryServerFailuresAndPaceEveryAttempt()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var unavailable = createResponse(503, "private-response");
    var retry = createResponse(502, "private-response");
    var success = createResponse(200, "{\"username\":\"seller\"}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(unavailable, retry, success);

    // act
    var response = client.getProfile("bearer-secret");

    // assert
    assertThat(response.username()).isEqualTo("seller");
    verify(httpClient, times(3))
        .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(pacerCalls.get()).isEqualTo(3);
  }

  @Test
  void getProfileShouldStopAfterMaximumServerRetries() throws IOException, InterruptedException {
    // arrange
    var response = createResponse(503, "private-response");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    var thrown = catchThrowable(() -> client.getProfile("bearer-secret"));

    // assert
    assertThat(thrown)
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("FetchTCG profile lookup is temporarily unavailable")
        .hasMessageNotContaining("bearer-secret")
        .hasMessageNotContaining("private-response");
    verify(httpClient, times(4))
        .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(pacerCalls.get()).isEqualTo(4);
  }

  @Test
  void getProfileShouldRepresentAuthenticationFailuresWithoutBody()
      throws IOException, InterruptedException {
    for (var status : new int[] {401, 403}) {
      // arrange
      var response = createResponse(status, "private-response-bearer-secret");
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(response);

      // act
      var thrown = catchThrowable(() -> client.getProfile("bearer-secret"));

      // assert
      assertThat(thrown)
          .isInstanceOf(FetchTcgAuthenticationException.class)
          .hasMessage("FetchTCG authentication failed with status " + status)
          .hasMessageNotContaining("bearer-secret")
          .hasMessageNotContaining("private-response");
    }
    assertThat(pacerCalls.get()).isEqualTo(2);
  }

  @Test
  void getProfileShouldNotRetryOtherStatusFailures() throws IOException, InterruptedException {
    // arrange
    var response = createResponse(429, "private-response");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    var thrown = catchThrowable(() -> client.getProfile("bearer-secret"));

    // assert
    assertThat(thrown)
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageNotContaining("private-response")
        .hasMessageNotContaining("bearer-secret");
    verify(httpClient).send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(pacerCalls.get()).isEqualTo(1);
  }

  @Test
  void getProfileShouldHideNetworkFailureDetails() throws IOException, InterruptedException {
    // arrange
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenThrow(new IOException("private-response bearer-secret"));

    // act
    var thrown = catchThrowable(() -> client.getProfile("bearer-secret"));

    // assert
    assertThat(thrown)
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageNotContaining("private-response")
        .hasMessageNotContaining("bearer-secret");
    assertThat(thrown.getCause()).isNull();
  }

  @Test
  void getProfileShouldRestoreInterruptFlagWithoutExposingDetails()
      throws IOException, InterruptedException {
    // arrange
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenThrow(new InterruptedException("bearer-secret"));

    // act
    var thrown = catchThrowable(() -> client.getProfile("bearer-secret"));

    // assert
    assertThat(thrown)
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageNotContaining("bearer-secret");
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  @Test
  void getProfileShouldMapInterruptionDuringPacing() {
    // arrange
    var interruptedClient =
        new HttpFetchTcgClient(
            URI.create("https://api.fetchtcg.example"),
            httpClient,
            new ObjectMapper(),
            () -> Thread.currentThread().interrupt());

    // act
    var thrown = catchThrowable(() -> interruptedClient.getProfile("bearer-secret"));

    // assert
    assertThat(thrown)
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("FetchTCG profile lookup was interrupted")
        .hasMessageNotContaining("bearer-secret");
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    verifyNoInteractions(httpClient);
  }

  @Test
  void getProfileShouldRejectMalformedOrMissingUsernameResponses()
      throws IOException, InterruptedException {
    for (var body :
        new String[] {"private-response-bearer-secret", "{\"username\":\" \"}", "{}", "null"}) {
      // arrange
      var response = createResponse(200, body);
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(response);

      // act
      var thrown = catchThrowable(() -> client.getProfile("bearer-secret"));

      // assert
      assertThat(thrown)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining("private-response")
          .hasMessageNotContaining("bearer-secret");
      assertThat(thrown.getCause()).isNull();
    }
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> createResponse(int statusCode, String body) {
    var response = (HttpResponse<String>) mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(statusCode);
    when(response.body()).thenReturn(body);
    return response;
  }
}
