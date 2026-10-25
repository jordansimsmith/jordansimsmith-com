package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class HttpFetchTcgClient {
  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
          + " (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
  private static final int MAX_RETRIES = 3;

  private final URI baseUri;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Runnable pacer;

  public HttpFetchTcgClient(
      URI baseUri, HttpClient httpClient, ObjectMapper objectMapper, Runnable pacer) {
    this.baseUri = baseUri;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.pacer = pacer;
  }

  public GetProfileResponse getProfile(String bearerToken) throws ServiceException {
    return doGetProfile(bearerToken);
  }

  private GetProfileResponse doGetProfile(String bearerToken) throws ServiceException {
    var request =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v1/private/profile"))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .GET()
            .build();

    var body = doExecute(request);
    GetProfileResponse response;
    try {
      response = objectMapper.readValue(body, GetProfileResponse.class);
    } catch (IOException e) {
      throw new IllegalStateException("FetchTCG returned a malformed profile response");
    }

    if (response == null || response.username() == null || response.username().isBlank()) {
      throw new IllegalStateException("FetchTCG profile did not include a username");
    }

    return response;
  }

  private String doExecute(HttpRequest request) throws ServiceException {
    for (var attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      try {
        pacer.run();
      } catch (RuntimeException e) {
        if (Thread.currentThread().isInterrupted()) {
          throw new ServiceUnavailableException("FetchTCG profile lookup was interrupted");
        }
        throw e;
      }
      if (Thread.currentThread().isInterrupted()) {
        throw new ServiceUnavailableException("FetchTCG profile lookup was interrupted");
      }

      HttpResponse<String> response;
      try {
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ServiceUnavailableException("FetchTCG profile lookup was interrupted");
      } catch (IOException e) {
        throw new ServiceUnavailableException("FetchTCG profile lookup is temporarily unavailable");
      }

      var statusCode = response.statusCode();
      if (statusCode >= 200 && statusCode < 300) {
        return response.body();
      }
      if (statusCode == 401 || statusCode == 403) {
        throw new FetchTcgAuthenticationException(statusCode);
      }
      if (statusCode >= 500 && attempt < MAX_RETRIES) {
        continue;
      }
      throw new ServiceUnavailableException("FetchTCG profile lookup is temporarily unavailable");
    }

    throw new ServiceUnavailableException("FetchTCG profile lookup is temporarily unavailable");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GetProfileResponse(@JsonProperty("username") String username) {}
}
