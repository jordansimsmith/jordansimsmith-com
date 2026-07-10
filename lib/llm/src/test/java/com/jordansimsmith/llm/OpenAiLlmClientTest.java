package com.jordansimsmith.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.FakeSecrets;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

public class OpenAiLlmClientTest {
  private static final String COMPLETION_RESPONSE =
      """
      {
        "id": "chatcmpl-123",
        "choices": [
          {
            "index": 0,
            "message": {
              "role": "assistant",
              "content": "{\\"verdict\\": \\"pass\\"}"
            }
          }
        ],
        "usage": {
          "prompt_tokens": 100,
          "completion_tokens": 20
        }
      }
      """;

  @Mock HttpClient httpClient;

  private ObjectMapper objectMapper;
  private FakeSecrets fakeSecrets;
  private OpenAiLlmClient client;
  private AutoCloseable openMocks;

  @BeforeEach
  void setUp() {
    openMocks = openMocks(this);
    objectMapper = new ObjectMapper();
    fakeSecrets = new FakeSecrets();
    fakeSecrets.set("my_service_api", "{\"openai_api_key\": \"test-api-key\"}");
    client =
        new OpenAiLlmClient(
            URI.create("https://api.openai.com"),
            objectMapper,
            fakeSecrets,
            "my_service_api",
            httpClient);
  }

  @AfterEach
  void tearDown() throws Exception {
    openMocks.close();
  }

  @Test
  void completeShouldSendRequestAndParseResponse() throws IOException, InterruptedException {
    // arrange
    var mockResponse = createMockResponse(200, COMPLETION_RESPONSE);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockResponse);

    var request =
        new LlmRequest(
            "gpt-5.4-mini",
            "low",
            true,
            List.of(LlmMessage.system("judge listings"), LlmMessage.user("Title: bulk lot")));

    // act
    var response = client.complete(request);

    // assert
    assertThat(response.content()).isEqualTo("{\"verdict\": \"pass\"}");
    assertThat(response.inputTokens()).isEqualTo(100);
    assertThat(response.outputTokens()).isEqualTo(20);

    var captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(captor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    var httpRequest = captor.getValue();
    assertThat(httpRequest.uri())
        .isEqualTo(URI.create("https://api.openai.com/v1/chat/completions"));
    assertThat(httpRequest.headers().firstValue("Authorization")).contains("Bearer test-api-key");
    assertThat(httpRequest.headers().firstValue("Content-Type")).contains("application/json");

    var body = objectMapper.readTree(requestBody(httpRequest));
    assertThat(body.get("model").asText()).isEqualTo("gpt-5.4-mini");
    assertThat(body.get("reasoning_effort").asText()).isEqualTo("low");
    assertThat(body.get("response_format").get("type").asText()).isEqualTo("json_object");
    assertThat(body.get("messages")).hasSize(2);
    assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("system");
    assertThat(body.get("messages").get(0).get("content").asText()).isEqualTo("judge listings");
    assertThat(body.get("messages").get(1).get("role").asText()).isEqualTo("user");
    assertThat(body.get("messages").get(1).get("content").asText()).isEqualTo("Title: bulk lot");
  }

  @Test
  void completeShouldOmitOptionalFieldsWhenUnset() throws IOException, InterruptedException {
    // arrange
    var mockResponse = createMockResponse(200, COMPLETION_RESPONSE);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockResponse);

    var request = new LlmRequest("gpt-5.4-mini", null, false, List.of(LlmMessage.user("hello")));

    // act
    client.complete(request);

    // assert
    var captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(captor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    var body = objectMapper.readTree(requestBody(captor.getValue()));
    assertThat(body.has("reasoning_effort")).isFalse();
    assertThat(body.has("response_format")).isFalse();
  }

  @Test
  void completeShouldThrowWhenRequestFails() throws IOException, InterruptedException {
    // arrange
    var mockResponse = createMockResponse(500, "{\"error\": \"internal\"}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(mockResponse);

    var request = new LlmRequest("gpt-5.4-mini", null, true, List.of(LlmMessage.user("hello")));

    // act & assert
    assertThatThrownBy(() -> client.complete(request))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IOException.class)
        .hasMessageContaining("status code 500");
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> createMockResponse(int statusCode, String body) {
    HttpResponse<String> mockResponse = mock(HttpResponse.class);
    when(mockResponse.statusCode()).thenReturn(statusCode);
    when(mockResponse.body()).thenReturn(body);
    return mockResponse;
  }

  private String requestBody(HttpRequest request) {
    var publisher = request.bodyPublisher().orElseThrow();
    var subscriber = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
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
    return subscriber.getBody().toCompletableFuture().join();
  }
}
