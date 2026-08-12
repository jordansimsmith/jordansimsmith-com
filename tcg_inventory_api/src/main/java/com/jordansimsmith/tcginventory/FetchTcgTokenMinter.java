package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jordansimsmith.secrets.Secrets;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class FetchTcgTokenMinter {
  static final String SECRET_NAME = "tcg_inventory";

  private final URI firebaseTokenUri;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Secrets secrets;

  public FetchTcgTokenMinter(
      URI firebaseTokenUri, HttpClient httpClient, ObjectMapper objectMapper, Secrets secrets) {
    this.firebaseTokenUri = firebaseTokenUri;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.secrets = secrets;
  }

  public String mint(String user) {
    try {
      return doMint(user);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private String doMint(String user) throws IOException, InterruptedException {
    var secretJson = secrets.get(SECRET_NAME);
    var secretNode = objectMapper.readTree(secretJson);
    var refreshToken = secretNode.get(user).asText();

    var requestBody =
        "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8");

    var request =
        HttpRequest.newBuilder()
            .uri(firebaseTokenUri)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new IOException(
          "Firebase token exchange failed with status "
              + response.statusCode()
              + ": "
              + response.body());
    }

    var tokenResponse = objectMapper.readValue(response.body(), FirebaseTokenResponse.class);

    if (tokenResponse.refreshToken() != null
        && !tokenResponse.refreshToken().equals(refreshToken)) {
      var updatedNode = (ObjectNode) secretNode.deepCopy();
      updatedNode.put(user, tokenResponse.refreshToken());
      secrets.put(SECRET_NAME, objectMapper.writeValueAsString(updatedNode));
    }

    return tokenResponse.idToken();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record FirebaseTokenResponse(
      @JsonProperty("id_token") String idToken,
      @JsonProperty("refresh_token") String refreshToken,
      @JsonProperty("expires_in") String expiresIn) {}
}
