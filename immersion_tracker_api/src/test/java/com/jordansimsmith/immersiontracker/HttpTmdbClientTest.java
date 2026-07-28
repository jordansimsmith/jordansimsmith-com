package com.jordansimsmith.immersiontracker;

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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

public class HttpTmdbClientTest {
  @Mock HttpClient httpClient;

  private ObjectMapper objectMapper;
  private Secrets secrets;
  private HttpTmdbClient client;
  private AutoCloseable openMocks;

  @BeforeEach
  void setUp() throws Exception {
    openMocks = openMocks(this);
    objectMapper = new ObjectMapper();
    secrets = new FakeSecrets();
    var secretJson =
        objectMapper.createObjectNode().put("tmdb_api_read_access_token", "test-access-token");
    ((FakeSecrets) secrets).set(HttpTmdbClient.SECRET, objectMapper.writeValueAsString(secretJson));
    client =
        new HttpTmdbClient(
            URI.create("https://api.themoviedb.org"), objectMapper, secrets, httpClient);
  }

  @AfterEach
  void tearDown() throws Exception {
    openMocks.close();
  }

  @Test
  void getMovieShouldReturnMovie() throws IOException, InterruptedException {
    // arrange
    var response =
        createMockResponse(
            200,
            """
            {
              "id": 372058,
              "title": "Your Name.",
              "original_title": "君の名は。",
              "poster_path": "/q719jXXEzOoYaps6babgKnONONX.jpg",
              "runtime": 106
            }
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    var movie = client.getMovie(372058);

    // assert
    assertThat(movie.id()).isEqualTo(372058);
    assertThat(movie.name()).isEqualTo("君の名は。");
    assertThat(movie.image())
        .isEqualTo("https://image.tmdb.org/t/p/w500/q719jXXEzOoYaps6babgKnONONX.jpg");
    assertThat(movie.duration()).isEqualTo(Duration.ofMinutes(106));

    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requestCaptor.getValue().uri())
        .isEqualTo(URI.create("https://api.themoviedb.org/3/movie/372058"));
    assertThat(requestCaptor.getValue().headers().firstValue("Authorization"))
        .contains("Bearer test-access-token");
  }

  @Test
  void getMovieShouldAllowMissingPoster() throws IOException, InterruptedException {
    // arrange
    var response =
        createMockResponse(
            200,
            """
            {
              "id": 372058,
              "title": "Your Name.",
              "original_title": "君の名は。",
              "poster_path": null,
              "runtime": 106
            }
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    var movie = client.getMovie(372058);

    // assert
    assertThat(movie.image()).isNull();
  }

  @Test
  void getMovieShouldThrowWhenRuntimeIsNotPositive() throws IOException, InterruptedException {
    // arrange
    var response =
        createMockResponse(
            200,
            """
            {
              "id": 372058,
              "title": "Your Name.",
              "original_title": "君の名は。",
              "poster_path": null,
              "runtime": 0
            }
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act & assert
    assertThatThrownBy(() -> client.getMovie(372058))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void getMovieShouldThrowWhenOriginalTitleIsBlank() throws IOException, InterruptedException {
    // arrange
    var response =
        createMockResponse(
            200,
            """
            {
              "id": 372058,
              "title": "Your Name.",
              "original_title": " ",
              "poster_path": null,
              "runtime": 106
            }
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act & assert
    assertThatThrownBy(() -> client.getMovie(372058))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void getMovieShouldThrowWhenRequestFails() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(404, "{\"status_message\":\"not found\"}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act & assert
    assertThatThrownBy(() -> client.getMovie(372058))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IOException.class)
        .hasMessageContaining("TMDB API request failed with status code 404");
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> createMockResponse(int statusCode, String body) {
    HttpResponse<String> mockResponse = mock(HttpResponse.class);
    when(mockResponse.statusCode()).thenReturn(statusCode);
    when(mockResponse.body()).thenReturn(body);
    return mockResponse;
  }
}
