package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.FakeSecrets;
import com.jordansimsmith.secrets.Secrets;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

public class HttpTvdbClientTest {
  private static final String LOGIN_RESPONSE =
      """
      {
        "status": "success",
        "data": {
          "token": "test-token"
        }
      }
      """;

  private static final String SERIES_RESPONSE =
      """
      {
        "status": "success",
        "data": {
          "name": "Test Show",
          "image": "https://example.com/show.jpg",
          "averageRuntime": 45
        }
      }
      """;

  private static final String MOVIE_RESPONSE =
      """
      {
        "status": "success",
        "data": {
          "name": "Test Movie",
          "image": "https://example.com/movie.jpg",
          "runtime": 123
        }
      }
      """;

  @Mock HttpClient httpClient;

  private ObjectMapper objectMapper;
  private Secrets secrets;
  private HttpTvdbClient client;
  private AutoCloseable openMocks;

  @BeforeEach
  void setUp() {
    openMocks = openMocks(this);
    objectMapper = new ObjectMapper();
    secrets = new FakeSecrets();
    client =
        new HttpTvdbClient(
            URI.create("https://api4.thetvdb.com"), objectMapper, secrets, httpClient);
  }

  @AfterEach
  void tearDown() throws Exception {
    openMocks.close();
  }

  @Test
  void getShowShouldReturnShow() throws Exception {
    // arrange
    var secretJson = objectMapper.createObjectNode().put("tvdb_api_key", "test-api-key");
    ((FakeSecrets) secrets).set(HttpTvdbClient.SECRET, objectMapper.writeValueAsString(secretJson));

    var loginResponse = createMockResponse(200, LOGIN_RESPONSE);
    var seriesResponse = createMockResponse(200, SERIES_RESPONSE);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(loginResponse)
        .thenReturn(seriesResponse);

    // act
    var show = client.getShow(123);

    // assert
    assertThat(show.id()).isEqualTo(123);
    assertThat(show.name()).isEqualTo("Test Show");
    assertThat(show.image()).isEqualTo("https://example.com/show.jpg");
    assertThat(show.averageRuntime()).isEqualTo(Duration.ofMinutes(45));
  }

  @Test
  void getShowShouldThrowWhenRuntimeMissing() throws Exception {
    // arrange
    var secretJson = objectMapper.createObjectNode().put("tvdb_api_key", "test-api-key");
    ((FakeSecrets) secrets).set(HttpTvdbClient.SECRET, objectMapper.writeValueAsString(secretJson));

    var loginResponse = createMockResponse(200, LOGIN_RESPONSE);
    var seriesResponse =
        createMockResponse(
            200,
            """
            {
              "status": "success",
              "data": {
                "name": "Test Show",
                "image": "https://example.com/show.jpg"
              }
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(loginResponse)
        .thenReturn(seriesResponse);

    // act & assert
    assertThatThrownBy(() -> client.getShow(123))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(NullPointerException.class);
  }

  @Test
  void getMovieShouldReturnMovie() throws Exception {
    // arrange
    var secretJson = objectMapper.createObjectNode().put("tvdb_api_key", "test-api-key");
    ((FakeSecrets) secrets).set(HttpTvdbClient.SECRET, objectMapper.writeValueAsString(secretJson));

    var loginResponse = createMockResponse(200, LOGIN_RESPONSE);
    var movieResponse = createMockResponse(200, MOVIE_RESPONSE);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(loginResponse)
        .thenReturn(movieResponse);

    // act
    var movie = client.getMovie(456);

    // assert
    assertThat(movie.id()).isEqualTo(456);
    assertThat(movie.name()).isEqualTo("Test Movie");
    assertThat(movie.image()).isEqualTo("https://example.com/movie.jpg");
    assertThat(movie.duration()).isEqualTo(Duration.ofMinutes(123));
  }

  @Test
  void getShowShouldThrowWhenLoginFails() throws Exception {
    // arrange
    var secretJson = objectMapper.createObjectNode().put("tvdb_api_key", "test-api-key");
    ((FakeSecrets) secrets).set(HttpTvdbClient.SECRET, objectMapper.writeValueAsString(secretJson));

    var loginResponse = createMockResponse(401, "{\"status\":\"failure\"}");

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(loginResponse);

    // act & assert
    assertThatThrownBy(() -> client.getShow(123))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IOException.class)
        .hasMessageContaining("tvdb.com login request failed with status code 401");
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> createMockResponse(int statusCode, String body) {
    HttpResponse<String> mockResponse = mock(HttpResponse.class);
    when(mockResponse.statusCode()).thenReturn(statusCode);
    when(mockResponse.body()).thenReturn(body);
    return mockResponse;
  }
}
