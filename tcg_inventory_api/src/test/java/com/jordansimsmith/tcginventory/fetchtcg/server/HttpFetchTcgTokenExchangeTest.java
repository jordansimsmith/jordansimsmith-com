package com.jordansimsmith.tcginventory.fetchtcg.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

public class HttpFetchTcgTokenExchangeTest {
  @Mock HttpClient httpClient;

  private ObjectMapper objectMapper;
  private HttpFetchTcgTokenExchange exchange;
  private AutoCloseable openMocks;

  @BeforeEach
  void setUp() {
    openMocks = openMocks(this);
    objectMapper = new ObjectMapper();
    exchange =
        new HttpFetchTcgTokenExchange(
            URI.create("https://securetoken.googleapis.com/v1/token?key=FAKE_API_KEY"),
            httpClient,
            objectMapper);
  }

  @AfterEach
  void tearDown() throws Exception {
    Thread.interrupted();
    openMocks.close();
  }

  @Test
  void exchangeShouldSendEncodedCandidateAndReturnRotatedTokens()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var response =
        createMockResponse(
            200,
            """
            {"id_token":"bearer-value","refresh_token":"rotated-value","other":"ignored"}
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    var result = exchange.exchange("candidate +/?&=");

    // assert
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(1))
        .send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    var request = requestCaptor.getValue();
    assertThat(request.uri())
        .isEqualTo(URI.create("https://securetoken.googleapis.com/v1/token?key=FAKE_API_KEY"));
    assertThat(request.headers().firstValue("Content-Type"))
        .contains("application/x-www-form-urlencoded");
    assertThat(formValue(readBody(request), "refresh_token")).isEqualTo("candidate +/?&=");
    assertThat(result.bearerToken()).isEqualTo("bearer-value");
    assertThat(result.refreshToken()).isEqualTo("rotated-value");
  }

  @Test
  void exchangeShouldRetainCandidateWhenFirebaseDoesNotRotateIt()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var response = createMockResponse(200, "{\"id_token\":\"bearer\"}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    var result = exchange.exchange("candidate-token");

    // assert
    assertThat(result.refreshToken()).isEqualTo("candidate-token");
  }

  @Test
  void exchangeShouldMapRejectedCandidateCodesToBadRequest()
      throws IOException, InterruptedException {
    for (var code : new String[] {"TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN"}) {
      // arrange
      var response = createMockResponse(400, "{\"error\":{\"message\":\"" + code + "\"}}");
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(response);

      // act & assert
      var thrown = catchThrowable(() -> exchange.exchange("secret-candidate"));
      assertThat(thrown)
          .isInstanceOf(BadRequestException.class)
          .hasMessage("The refresh token was rejected. Check it and try again.")
          .hasMessageNotContaining(code)
          .hasMessageNotContaining("secret-candidate");
      assertThat(stackTrace(thrown)).doesNotContain(code, "secret-candidate");
    }
  }

  @Test
  void exchangeShouldMapOtherFirebaseFailuresWithoutExposingResponseBody()
      throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(503, "sensitive-provider-response");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act & assert
    var thrown = catchThrowable(() -> exchange.exchange("secret-candidate"));
    assertThat(thrown)
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("Firebase token exchange is temporarily unavailable")
        .hasMessageNotContaining("secret-candidate")
        .hasMessageNotContaining("sensitive-provider-response");
    assertThat(stackTrace(thrown))
        .doesNotContain("secret-candidate", "sensitive-provider-response");
  }

  @Test
  void exchangeShouldMapNetworkFailureWithoutExposingCandidate()
      throws IOException, InterruptedException {
    // arrange
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenThrow(new IOException("failure containing secret-candidate"));

    // act & assert
    var thrown = catchThrowable(() -> exchange.exchange("secret-candidate"));
    assertThat(thrown)
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageNotContaining("secret-candidate");
    assertThat(stackTrace(thrown)).doesNotContain("secret-candidate");
  }

  @Test
  void exchangeShouldRestoreInterruptFlagAndHideFailureDetails()
      throws IOException, InterruptedException {
    // arrange
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenThrow(new InterruptedException("secret-candidate"));

    // act
    var thrown = catchThrowable(() -> exchange.exchange("secret-candidate"));

    // assert
    assertThat(thrown)
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageNotContaining("secret-candidate");
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  @Test
  void exchangeShouldFailSafelyOnMalformedOrIncompleteSuccessResponses()
      throws IOException, InterruptedException {
    for (var body :
        new String[] {
          "response-body-secret",
          "{\"id_token\":\" \"}",
          "{\"id_token\":\"bearer\",\"refresh_token\":\" \"}"
        }) {
      // arrange
      var response = createMockResponse(200, body);
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(response);

      // act & assert
      var thrown = catchThrowable(() -> exchange.exchange("candidate-secret"));
      assertThat(thrown)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Firebase returned a malformed token response")
          .hasMessageNotContaining("candidate-secret")
          .hasMessageNotContaining("response-body-secret");
      assertThat(stackTrace(thrown)).doesNotContain("candidate-secret", "response-body-secret");
    }
  }

  @Test
  void exchangeResultShouldRedactBothTokensFromString() {
    // arrange
    var result =
        new HttpFetchTcgTokenExchange.TokenExchangeResult("bearer-secret", "refresh-secret");

    // act
    var diagnostic = result.toString();

    // assert
    assertThat(diagnostic).contains("<redacted>").doesNotContain("bearer-secret", "refresh-secret");
  }

  private String formValue(String body, String name) {
    return Arrays.stream(body.split("&"))
        .filter(field -> field.startsWith(name + "="))
        .map(field -> URLDecoder.decode(field.substring(name.length() + 1), StandardCharsets.UTF_8))
        .findFirst()
        .orElseThrow();
  }

  private String stackTrace(Throwable throwable) {
    var stringWriter = new StringWriter();
    try (var printWriter = new PrintWriter(stringWriter)) {
      throwable.printStackTrace(printWriter);
    }
    return stringWriter.toString();
  }

  private String readBody(HttpRequest request) {
    var body = new StringBuilder();
    var completed = new CompletableFuture<String>();
    request
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ByteBuffer buffer) {
                var bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                body.append(new String(bytes, StandardCharsets.UTF_8));
              }

              @Override
              public void onError(Throwable throwable) {
                completed.completeExceptionally(throwable);
              }

              @Override
              public void onComplete() {
                completed.complete(body.toString());
              }
            });
    return completed.join();
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> createMockResponse(int statusCode, String body) {
    HttpResponse<String> mockResponse = mock(HttpResponse.class);
    when(mockResponse.statusCode()).thenReturn(statusCode);
    when(mockResponse.body()).thenReturn(body);
    return mockResponse;
  }
}
