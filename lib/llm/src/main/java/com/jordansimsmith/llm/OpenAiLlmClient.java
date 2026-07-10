package com.jordansimsmith.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Verify;
import com.jordansimsmith.secrets.Secrets;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

public class OpenAiLlmClient implements LlmClient {
  private final URI baseUri;
  private final ObjectMapper objectMapper;
  private final Secrets secrets;
  private final String secretName;
  private final HttpClient httpClient;

  public OpenAiLlmClient(
      URI baseUri,
      ObjectMapper objectMapper,
      Secrets secrets,
      String secretName,
      HttpClient httpClient) {
    this.baseUri = baseUri;
    this.objectMapper = objectMapper;
    this.secrets = secrets;
    this.secretName = secretName;
    this.httpClient = httpClient;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record ChatCompletionRequest(
      @JsonProperty("model") String model,
      @JsonProperty("messages") List<ChatMessage> messages,
      @Nullable @JsonProperty("reasoning_effort") String reasoningEffort,
      @Nullable @JsonProperty("response_format") ResponseFormat responseFormat) {}

  private record ChatMessage(
      @JsonProperty("role") String role, @JsonProperty("content") String content) {}

  private record ResponseFormat(@JsonProperty("type") String type) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ChatCompletionResponse(
      @JsonProperty("choices") List<Choice> choices, @JsonProperty("usage") Usage usage) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Choice(@JsonProperty("message") Message message) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Message(@JsonProperty("content") String content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Usage(
      @JsonProperty("prompt_tokens") long promptTokens,
      @JsonProperty("completion_tokens") long completionTokens) {}

  @Override
  public LlmResponse complete(LlmRequest request) {
    try {
      return doComplete(request);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private LlmResponse doComplete(LlmRequest request) throws IOException, InterruptedException {
    var secret = secrets.get(secretName);
    var apiKey = objectMapper.readTree(secret).get("openai_api_key").asText(null);
    Verify.verifyNotNull(apiKey, "openai_api_key not found in secret %s", secretName);

    var messages =
        request.messages().stream()
            .map(m -> new ChatMessage(m.role().name().toLowerCase(Locale.ROOT), m.content()))
            .toList();
    var responseFormat = request.jsonResponse() ? new ResponseFormat("json_object") : null;
    var completionRequest =
        new ChatCompletionRequest(
            request.model(), messages, request.reasoningEffort(), responseFormat);

    var httpRequest =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v1/chat/completions"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(completionRequest)))
            .build();

    var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

    if (httpResponse.statusCode() != 200) {
      throw new IOException(
          "OpenAI chat completions request failed with status code "
              + httpResponse.statusCode()
              + " and body: "
              + httpResponse.body());
    }

    var completion = objectMapper.readValue(httpResponse.body(), ChatCompletionResponse.class);
    Verify.verify(!completion.choices().isEmpty(), "expected at least 1 choice in response");
    var content = completion.choices().get(0).message().content();
    var usage = completion.usage();
    return new LlmResponse(content, usage.promptTokens(), usage.completionTokens());
  }
}
