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

public class HttpTmdbClient implements TmdbClient {
  @VisibleForTesting static final String SECRET = "immersion_tracker_api";
  private static final String IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500";

  private final URI baseUri;
  private final ObjectMapper objectMapper;
  private final Secrets secrets;
  private final HttpClient httpClient;

  public HttpTmdbClient(
      URI baseUri, ObjectMapper objectMapper, Secrets secrets, HttpClient httpClient) {
    this.baseUri = baseUri;
    this.objectMapper = objectMapper;
    this.secrets = secrets;
    this.httpClient = httpClient;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record MovieResponse(
      @JsonProperty("id") int id,
      @JsonProperty("title") String title,
      @JsonProperty("poster_path") String posterPath,
      @JsonProperty("runtime") Integer runtime) {}

  @Override
  public Movie getMovie(int id) {
    try {
      return doGetMovie(id);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Movie doGetMovie(int id) throws IOException, InterruptedException {
    var secret = secrets.get(SECRET);
    var accessToken = objectMapper.readTree(secret).get("tmdb_api_read_access_token").asText(null);
    Preconditions.checkNotNull(accessToken, "tmdb_api_read_access_token not found in secret");

    var request =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/3/movie/" + id))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException(
          "TMDB API request failed with status code "
              + response.statusCode()
              + " and body: "
              + response.body());
    }

    var movie = objectMapper.readValue(response.body(), MovieResponse.class);
    Preconditions.checkState(movie.id() == id, "Expected movie ID %s, got %s", id, movie.id());
    Preconditions.checkNotNull(movie.title(), "Movie title is null");
    Preconditions.checkState(!movie.title().isBlank(), "Movie title is blank");
    Preconditions.checkNotNull(movie.runtime(), "Movie runtime is null");
    Preconditions.checkState(movie.runtime() > 0, "Movie runtime must be positive");

    var image =
        movie.posterPath() == null || movie.posterPath().isBlank()
            ? null
            : IMAGE_BASE_URL + movie.posterPath();
    return new Movie(movie.id(), movie.title(), image, Duration.ofMinutes(movie.runtime()));
  }
}
