package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class HttpFetchTcgTokenExchange {
  private final URI firebaseTokenUri;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public HttpFetchTcgTokenExchange(
      URI firebaseTokenUri, HttpClient httpClient, ObjectMapper objectMapper) {
    this.firebaseTokenUri = firebaseTokenUri;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  public TokenExchangeResult exchange(String refreshToken) throws ServiceException {
    var requestBody =
        "grant_type=refresh_token&refresh_token="
            + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);
    var request =
        HttpRequest.newBuilder()
            .uri(firebaseTokenUri)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceUnavailableException("Firebase token exchange was interrupted");
    } catch (IOException e) {
      throw new ServiceUnavailableException("Firebase token exchange is temporarily unavailable");
    }

    if (response.statusCode() != 200) {
      var errorCode = readFirebaseErrorCode(response.body());
      if ("TOKEN_EXPIRED".equals(errorCode) || "INVALID_REFRESH_TOKEN".equals(errorCode)) {
        throw new BadRequestException("The refresh token was rejected. Check it and try again.");
      }
      throw new ServiceUnavailableException("Firebase token exchange is temporarily unavailable");
    }

    var tokenResponse = readTokenResponse(response.body());
    if (tokenResponse == null
        || tokenResponse.idToken() == null
        || tokenResponse.idToken().isBlank()) {
      throw new IllegalStateException("Firebase returned a malformed token response");
    }
    if (tokenResponse.refreshToken() != null && tokenResponse.refreshToken().isBlank()) {
      throw new IllegalStateException("Firebase returned a malformed token response");
    }

    var effectiveRefreshToken =
        tokenResponse.refreshToken() == null ? refreshToken : tokenResponse.refreshToken();
    return new TokenExchangeResult(tokenResponse.idToken(), effectiveRefreshToken);
  }

  private String readFirebaseErrorCode(String responseBody) {
    try {
      var errorResponse = objectMapper.readTree(responseBody);
      return errorResponse == null ? "" : errorResponse.path("error").path("message").asText();
    } catch (IOException e) {
      return "";
    }
  }

  private FirebaseTokenResponse readTokenResponse(String responseBody) {
    try {
      return objectMapper.readValue(responseBody, FirebaseTokenResponse.class);
    } catch (IOException e) {
      throw new IllegalStateException("Firebase returned a malformed token response");
    }
  }

  public record TokenExchangeResult(String bearerToken, String refreshToken) {
    @Override
    public String toString() {
      return "TokenExchangeResult[bearerToken=<redacted>, refreshToken=<redacted>]";
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record FirebaseTokenResponse(
      @JsonProperty("id_token") String idToken,
      @JsonProperty("refresh_token") String refreshToken) {
    @Override
    public String toString() {
      return "FirebaseTokenResponse[idToken=<redacted>, refreshToken=<redacted>]";
    }
  }
}
