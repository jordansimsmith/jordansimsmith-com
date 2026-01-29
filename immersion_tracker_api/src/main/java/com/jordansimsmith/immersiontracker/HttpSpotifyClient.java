package com.jordansimsmith.immersiontracker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.jordansimsmith.secrets.Secrets;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

public class HttpSpotifyClient implements SpotifyClient {
  @VisibleForTesting static final String SECRET = "immersion_tracker_api";

  private final ObjectMapper objectMapper;
  private final Secrets secrets;
  private final HttpClient httpClient;

  public HttpSpotifyClient(ObjectMapper objectMapper, Secrets secrets, HttpClient httpClient) {
    this.objectMapper = objectMapper;
    this.secrets = secrets;
    this.httpClient = httpClient;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record TokenResponse(@JsonProperty("access_token") String accessToken) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record EpisodeResponse(
      @JsonProperty("id") String id,
      @JsonProperty("name") String name,
      @JsonProperty("duration_ms") Long durationMs,
      @JsonProperty("show") EpisodeShow show) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record EpisodeShow(
      @JsonProperty("id") String id,
      @JsonProperty("name") String name,
      @JsonProperty("images") List<SpotifyImage> images) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SpotifyImage(@JsonProperty("url") String url) {}

  @Override
  public Episode getEpisode(String episodeId) {
    try {
      return doGetEpisode(episodeId);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Episode doGetEpisode(String episodeId) throws Exception {
    var accessToken = getAccessToken();

    var request =
        HttpRequest.newBuilder()
            .uri(new URI("https://api.spotify.com/v1/episodes/" + episodeId))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new IOException(
          "Spotify API request failed with status code "
              + response.statusCode()
              + " and body: "
              + response.body());
    }

    var episodeResponse = objectMapper.readValue(response.body(), EpisodeResponse.class);

    Preconditions.checkState(
        episodeId.equals(episodeResponse.id()),
        "Expected episode ID %s, got %s",
        episodeId,
        episodeResponse.id());

    Preconditions.checkNotNull(episodeResponse.name(), "Episode name is null");
    Preconditions.checkNotNull(episodeResponse.show(), "Episode show is null");
    Preconditions.checkNotNull(episodeResponse.show().id(), "Show ID is null");
    Preconditions.checkNotNull(episodeResponse.show().name(), "Show name is null");
    Preconditions.checkNotNull(episodeResponse.durationMs(), "Episode duration is null");

    var duration = Duration.ofMillis(episodeResponse.durationMs());
    var showArtworkUrl = selectFirstImageUrl(episodeResponse.show().images());

    return new Episode(
        episodeResponse.id(),
        episodeResponse.name(),
        episodeResponse.show().id(),
        episodeResponse.show().name(),
        showArtworkUrl,
        duration);
  }

  private String selectFirstImageUrl(List<SpotifyImage> images) {
    if (images == null || images.isEmpty()) {
      return null;
    }
    var first = images.get(0);
    return first != null ? first.url() : null;
  }

  private String getAccessToken() throws Exception {
    var secret = secrets.get(SECRET);
    var secretTree = objectMapper.readTree(secret);
    var clientId = secretTree.get("spotify_client_id").asText(null);
    var clientSecret = secretTree.get("spotify_client_secret").asText(null);
    Preconditions.checkNotNull(clientId, "spotify_client_id not found in secret");
    Preconditions.checkNotNull(clientSecret, "spotify_client_secret not found in secret");

    var credentials = clientId + ":" + clientSecret;
    var encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());

    var request =
        HttpRequest.newBuilder()
            .uri(new URI("https://accounts.spotify.com/api/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", "Basic " + encodedCredentials)
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new IOException(
          "Spotify token request failed with status code "
              + response.statusCode()
              + " and body: "
              + response.body());
    }

    var tokenResponse = objectMapper.readValue(response.body(), TokenResponse.class);
    Preconditions.checkNotNull(tokenResponse.accessToken(), "Access token is null");

    return tokenResponse.accessToken();
  }
}
