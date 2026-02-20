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

public class HttpTvdbClient implements TvdbClient {
  @VisibleForTesting static final String SECRET = "immersion_tracker_api";

  private final URI baseUri;
  private final ObjectMapper objectMapper;
  private final Secrets secrets;
  private final HttpClient httpClient;

  public HttpTvdbClient(
      URI baseUri, ObjectMapper objectMapper, Secrets secrets, HttpClient httpClient) {
    this.baseUri = baseUri;
    this.objectMapper = objectMapper;
    this.secrets = secrets;
    this.httpClient = httpClient;
  }

  private record LoginRequest(@JsonProperty("apikey") String apiKey) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record LoginResponse(
      @JsonProperty("status") String status, @JsonProperty("data") LoginData data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record LoginData(@JsonProperty("token") String token) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SeriesResponse(
      @JsonProperty("status") String status, @JsonProperty("data") SeriesData data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record SeriesData(
      @JsonProperty("name") String name,
      @JsonProperty String image,
      @JsonProperty("averageRuntime") Integer averageRuntimeMinutes) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record MovieResponse(
      @JsonProperty("status") String status, @JsonProperty("data") MovieData data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record MovieData(
      @JsonProperty("name") String name,
      @JsonProperty String image,
      @JsonProperty("runtime") Integer runtimeMinutes) {}

  @Override
  public Show getShow(int id) {
    try {
      return doGetShow(id);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Movie getMovie(int id) {
    try {
      return doGetMovie(id);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Show doGetShow(int id) throws Exception {
    var token = getToken();

    var seriesReq =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v4/series/" + id))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    var seriesRes = httpClient.send(seriesReq, HttpResponse.BodyHandlers.ofString());

    if (seriesRes.statusCode() != 200) {
      throw new IOException(
          "tvdb.com series request failed with status code " + seriesRes.statusCode());
    }

    var seriesResBody = objectMapper.readValue(seriesRes.body(), SeriesResponse.class);
    if (!seriesResBody.status.equals("success")) {
      throw new IOException("tvdb.com series request failed with status " + seriesResBody.status);
    }

    Preconditions.checkNotNull(seriesResBody.data.name);
    Preconditions.checkNotNull(seriesResBody.data.image);
    Preconditions.checkNotNull(seriesResBody.data.averageRuntimeMinutes);
    return new Show(
        id,
        seriesResBody.data.name,
        seriesResBody.data.image,
        Duration.ofMinutes(seriesResBody.data.averageRuntimeMinutes));
  }

  private Movie doGetMovie(int id) throws Exception {
    var token = getToken();

    var movieReq =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v4/movies/" + id))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    var movieRes = httpClient.send(movieReq, HttpResponse.BodyHandlers.ofString());

    if (movieRes.statusCode() != 200) {
      throw new IOException(
          "tvdb.com movie request failed with status code " + movieRes.statusCode());
    }

    var movieResBody = objectMapper.readValue(movieRes.body(), MovieResponse.class);
    if (!movieResBody.status.equals("success")) {
      throw new IOException("tvdb.com movie request failed with status " + movieResBody.status);
    }

    Preconditions.checkNotNull(movieResBody.data.name);
    Preconditions.checkNotNull(movieResBody.data.image);
    Preconditions.checkNotNull(movieResBody.data.runtimeMinutes);
    var duration = Duration.ofMinutes(movieResBody.data.runtimeMinutes);
    return new Movie(id, movieResBody.data.name, movieResBody.data.image, duration);
  }

  private String getToken() throws Exception {
    var secret = secrets.get(SECRET);
    var apikey = objectMapper.readTree(secret).get("tvdb_api_key").asText(null);
    Preconditions.checkNotNull(apikey);

    var loginReq =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v4/login"))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(new LoginRequest(apikey))))
            .build();
    var loginRes = httpClient.send(loginReq, HttpResponse.BodyHandlers.ofString());

    if (loginRes.statusCode() != 200) {
      throw new IOException(
          "tvdb.com login request failed with status code " + loginRes.statusCode());
    }

    var loginResBody = objectMapper.readValue(loginRes.body(), LoginResponse.class);
    if (!loginResBody.status.equals("success")) {
      throw new IOException("tvdb.com login request failed with status " + loginResBody.status);
    }

    var token = loginResBody.data.token;
    Preconditions.checkNotNull(token);

    return token;
  }
}
