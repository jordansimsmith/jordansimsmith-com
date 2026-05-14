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
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

public class HttpSpotifyClientTest {
  private static final String TOKEN_RESPONSE =
      """
      {
        "access_token": "test_access_token_placeholder",
        "token_type": "Bearer",
        "expires_in": 3600
      }
      """;

  private static final String EPISODE_RESPONSE =
      """
      {
        "audio_preview_url": "https://podz-content.spotifycdn.com/audio/clips/6yQ7QIL56Thi5G6xB6GjXW/clip_266423_326423.mp3",
        "description": "Episode description",
        "duration_ms": 388284,
        "explicit": false,
        "external_urls": {
          "spotify": "https://open.spotify.com/episode/4qjerzMw8jfD30VOG0tjpK"
        },
        "href": "https://api.spotify.com/v1/episodes/4qjerzMw8jfD30VOG0tjpK",
        "id": "4qjerzMw8jfD30VOG0tjpK",
        "images": [],
        "is_externally_hosted": false,
        "is_playable": true,
        "language": "en-US",
        "languages": ["en-US"],
        "name": "No 1 紹介(しょうかい) Introduction",
        "release_date": "2021-03-28",
        "release_date_precision": "day",
        "show": {
          "available_markets": [],
          "copyrights": [],
          "description": "Show description",
          "explicit": false,
          "external_urls": {
            "spotify": "https://open.spotify.com/show/6Nl8RDfPxsk4h4bfWe76Kg"
          },
          "href": "https://api.spotify.com/v1/shows/6Nl8RDfPxsk4h4bfWe76Kg",
          "id": "6Nl8RDfPxsk4h4bfWe76Kg",
          "images": [
            {"url": "https://i.scdn.co/image/ab6765630000ba8a1234", "height": 640, "width": 640},
            {"url": "https://i.scdn.co/image/ab6765630000ba8a5678", "height": 300, "width": 300}
          ],
          "is_externally_hosted": false,
          "languages": ["en-US"],
          "media_type": "audio",
          "name": "The Miku Real Japanese Podcast",
          "publisher": "Miku",
          "total_episodes": 201,
          "type": "show",
          "uri": "spotify:show:6Nl8RDfPxsk4h4bfWe76Kg"
        },
        "type": "episode",
        "uri": "spotify:episode:4qjerzMw8jfD30VOG0tjpK"
      }
      """;

  private static final String EPISODE_RESPONSE_SHORT_DURATION =
      """
      {
        "id": "testEpisodeId",
        "name": "Short Episode",
        "duration_ms": 1000,
        "release_date": "2021-03-28",
        "release_date_precision": "day",
        "show": {
          "id": "testShowId",
          "name": "Test Show"
        }
      }
      """;

  private static final String EPISODE_RESPONSE_LONG_DURATION =
      """
      {
        "id": "testEpisodeId",
        "name": "Long Episode",
        "duration_ms": 3600000,
        "release_date": "2021-03-28",
        "release_date_precision": "day",
        "show": {
          "id": "testShowId",
          "name": "Test Show"
        }
      }
      """;

  @Mock HttpClient httpClient;

  private ObjectMapper objectMapper;
  private Secrets secrets;
  private HttpSpotifyClient client;
  private AutoCloseable openMocks;

  @BeforeEach
  void setUp() {
    openMocks = openMocks(this);
    objectMapper = new ObjectMapper();
    secrets = new FakeSecrets();
    client =
        new HttpSpotifyClient(
            URI.create("https://api.spotify.com"),
            URI.create("https://accounts.spotify.com"),
            objectMapper,
            secrets,
            httpClient);
  }

  @AfterEach
  void tearDown() throws Exception {
    openMocks.close();
  }

  @Test
  void getEpisodeShouldReturnEpisodeWithAllFields() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var episodeResponse = createMockResponse(200, EPISODE_RESPONSE);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(episodeResponse);

    // act
    var episode = client.getEpisode("4qjerzMw8jfD30VOG0tjpK");

    // assert
    assertThat(episode.id()).isEqualTo("4qjerzMw8jfD30VOG0tjpK");
    assertThat(episode.title()).isEqualTo("No 1 紹介(しょうかい) Introduction");
    assertThat(episode.showId()).isEqualTo("6Nl8RDfPxsk4h4bfWe76Kg");
    assertThat(episode.showName()).isEqualTo("The Miku Real Japanese Podcast");
    assertThat(episode.showArtworkUrl()).isEqualTo("https://i.scdn.co/image/ab6765630000ba8a1234");
    assertThat(episode.duration()).isEqualTo(Duration.ofMillis(388284));
    assertThat(episode.releaseDate()).isEqualTo(LocalDate.of(2021, 3, 28));
  }

  @Test
  void getEpisodeShouldConvertShortDuration() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var episodeResponse = createMockResponse(200, EPISODE_RESPONSE_SHORT_DURATION);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(episodeResponse);

    // act
    var episode = client.getEpisode("testEpisodeId");

    // assert
    assertThat(episode.duration()).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void getEpisodeShouldConvertLongDuration() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var episodeResponse = createMockResponse(200, EPISODE_RESPONSE_LONG_DURATION);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(episodeResponse);

    // act
    var episode = client.getEpisode("testEpisodeId");

    // assert
    assertThat(episode.duration()).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void getEpisodeShouldParseMonthPrecisionReleaseDate() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var episodeResponse =
        createMockResponse(
            200,
            """
            {
              "id": "testEpisodeId",
              "name": "Test Episode",
              "duration_ms": 388284,
              "release_date": "2021-03",
              "release_date_precision": "month",
              "show": {
                "id": "testShowId",
                "name": "Test Show"
              }
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(episodeResponse);

    // act
    var episode = client.getEpisode("testEpisodeId");

    // assert
    assertThat(episode.releaseDate()).isEqualTo(LocalDate.of(2021, 3, 1));
  }

  @Test
  void getEpisodeShouldParseYearPrecisionReleaseDate() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var episodeResponse =
        createMockResponse(
            200,
            """
            {
              "id": "testEpisodeId",
              "name": "Test Episode",
              "duration_ms": 388284,
              "release_date": "2021",
              "release_date_precision": "year",
              "show": {
                "id": "testShowId",
                "name": "Test Show"
              }
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(episodeResponse);

    // act
    var episode = client.getEpisode("testEpisodeId");

    // assert
    assertThat(episode.releaseDate()).isEqualTo(LocalDate.of(2021, 1, 1));
  }

  @Test
  void getEpisodeShouldThrowWhenTokenRequestFails() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(401, "{\"error\":\"invalid_client\"}");

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse);

    // act & assert
    assertThatThrownBy(() -> client.getEpisode("testEpisodeId"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IOException.class)
        .hasMessageContaining("Spotify token request failed with status code 401");
  }

  @Test
  void getEpisodeShouldThrowWhenEpisodeRequestFails() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var episodeResponse =
        createMockResponse(404, "{\"error\":{\"status\":404,\"message\":\"Not found.\"}}");

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(episodeResponse);

    // act & assert
    assertThatThrownBy(() -> client.getEpisode("nonexistent"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IOException.class)
        .hasMessageContaining("Spotify API request failed with status code 404");
  }

  @Test
  void getEpisodeShouldThrowWhenEpisodeNameIsNull() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var episodeResponse =
        createMockResponse(
            200,
            """
            {
              "id": "testEpisodeId",
              "name": null,
              "duration_ms": 388284,
              "release_date": "2021-03-28",
              "release_date_precision": "day",
              "show": {
                "id": "testShowId",
                "name": "Test Show"
              }
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(episodeResponse);

    // act & assert
    assertThatThrownBy(() -> client.getEpisode("testEpisodeId"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(NullPointerException.class)
        .hasMessageContaining("Episode name is null");
  }

  @Test
  void getEpisodeShouldThrowWhenShowIsNull() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var episodeResponse =
        createMockResponse(
            200,
            """
            {
              "id": "testEpisodeId",
              "name": "Test Episode",
              "duration_ms": 388284,
              "release_date": "2021-03-28",
              "release_date_precision": "day",
              "show": null
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(episodeResponse);

    // act & assert
    assertThatThrownBy(() -> client.getEpisode("testEpisodeId"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(NullPointerException.class)
        .hasMessageContaining("Episode show is null");
  }

  @Test
  void getEpisodeShouldThrowWhenShowIdIsNull() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var episodeResponse =
        createMockResponse(
            200,
            """
            {
              "id": "testEpisodeId",
              "name": "Test Episode",
              "duration_ms": 388284,
              "release_date": "2021-03-28",
              "release_date_precision": "day",
              "show": {
                "id": null,
                "name": "Test Show"
              }
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(episodeResponse);

    // act & assert
    assertThatThrownBy(() -> client.getEpisode("testEpisodeId"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(NullPointerException.class)
        .hasMessageContaining("Show ID is null");
  }

  @Test
  void getEpisodeShouldThrowWhenShowNameIsNull() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var episodeResponse =
        createMockResponse(
            200,
            """
            {
              "id": "testEpisodeId",
              "name": "Test Episode",
              "duration_ms": 388284,
              "release_date": "2021-03-28",
              "release_date_precision": "day",
              "show": {
                "id": "testShowId",
                "name": null
              }
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(episodeResponse);

    // act & assert
    assertThatThrownBy(() -> client.getEpisode("testEpisodeId"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(NullPointerException.class)
        .hasMessageContaining("Show name is null");
  }

  @Test
  void getEpisodeShouldThrowWhenDurationIsNull() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var episodeResponse =
        createMockResponse(
            200,
            """
            {
              "id": "testEpisodeId",
              "name": "Test Episode",
              "duration_ms": null,
              "release_date": "2021-03-28",
              "release_date_precision": "day",
              "show": {
                "id": "testShowId",
                "name": "Test Show"
              }
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(episodeResponse);

    // act & assert
    assertThatThrownBy(() -> client.getEpisode("testEpisodeId"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(NullPointerException.class)
        .hasMessageContaining("Episode duration is null");
  }

  @Test
  void getEpisodeShouldThrowWhenReleaseDateIsNull() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var episodeResponse =
        createMockResponse(
            200,
            """
            {
              "id": "testEpisodeId",
              "name": "Test Episode",
              "duration_ms": 388284,
              "release_date": null,
              "release_date_precision": "day",
              "show": {
                "id": "testShowId",
                "name": "Test Show"
              }
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(episodeResponse);

    // act & assert
    assertThatThrownBy(() -> client.getEpisode("testEpisodeId"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(NullPointerException.class)
        .hasMessageContaining("Episode release date is null");
  }

  @Test
  void getEpisodeShouldThrowWhenAccessTokenIsNull() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, "{\"access_token\":null}");

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse);

    // act & assert
    assertThatThrownBy(() -> client.getEpisode("testEpisodeId"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(NullPointerException.class)
        .hasMessageContaining("Access token is null");
  }

  @Test
  void getEpisodeShouldThrowWhenEpisodeIdMismatch() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var episodeResponse =
        createMockResponse(
            200,
            """
            {
              "id": "differentEpisodeId",
              "name": "Test Episode",
              "duration_ms": 388284,
              "release_date": "2021-03-28",
              "release_date_precision": "day",
              "show": {
                "id": "testShowId",
                "name": "Test Show"
              }
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(episodeResponse);

    // act & assert
    assertThatThrownBy(() -> client.getEpisode("testEpisodeId"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Expected episode ID testEpisodeId, got differentEpisodeId");
  }

  @Test
  void findShowEpisodesShouldReturnSinglePageOfEpisodes() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var pageResponse =
        createMockResponse(
            200,
            """
            {
              "items": [
                {
                  "id": "episode1",
                  "name": "Episode 1",
                  "duration_ms": 60000,
                  "release_date": "2021-03-28",
                  "release_date_precision": "day"
                },
                {
                  "id": "episode2",
                  "name": "Episode 2",
                  "duration_ms": 120000,
                  "release_date": "2021-04-04",
                  "release_date_precision": "day"
                }
              ],
              "next": null
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(pageResponse);

    // act
    var episodes = client.findShowEpisodes("testShowId");

    // assert
    assertThat(episodes).hasSize(2);
    assertThat(episodes.get(0).id()).isEqualTo("episode1");
    assertThat(episodes.get(0).title()).isEqualTo("Episode 1");
    assertThat(episodes.get(0).duration()).isEqualTo(Duration.ofMinutes(1));
    assertThat(episodes.get(0).releaseDate()).isEqualTo(LocalDate.of(2021, 3, 28));
    assertThat(episodes.get(1).id()).isEqualTo("episode2");
    assertThat(episodes.get(1).releaseDate()).isEqualTo(LocalDate.of(2021, 4, 4));
  }

  @Test
  void findShowEpisodesShouldFollowNextPaginationLink() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var firstPage =
        createMockResponse(
            200,
            """
            {
              "items": [
                {
                  "id": "episode1",
                  "name": "Episode 1",
                  "duration_ms": 60000,
                  "release_date": "2021-03-28",
                  "release_date_precision": "day"
                }
              ],
              "next": "https://api.spotify.com/v1/shows/testShowId/episodes?limit=50&offset=50"
            }
            """);
    var secondPage =
        createMockResponse(
            200,
            """
            {
              "items": [
                {
                  "id": "episode2",
                  "name": "Episode 2",
                  "duration_ms": 120000,
                  "release_date": "2021-04-04",
                  "release_date_precision": "day"
                }
              ],
              "next": null
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(firstPage)
        .thenReturn(secondPage);

    // act
    var episodes = client.findShowEpisodes("testShowId");

    // assert
    assertThat(episodes).hasSize(2);
    assertThat(episodes.get(0).id()).isEqualTo("episode1");
    assertThat(episodes.get(1).id()).isEqualTo("episode2");
  }

  @Test
  void findShowEpisodesShouldParseMixedReleaseDatePrecisions() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var pageResponse =
        createMockResponse(
            200,
            """
            {
              "items": [
                {
                  "id": "dayEpisode",
                  "name": "Day Episode",
                  "duration_ms": 60000,
                  "release_date": "2021-03-28",
                  "release_date_precision": "day"
                },
                {
                  "id": "monthEpisode",
                  "name": "Month Episode",
                  "duration_ms": 60000,
                  "release_date": "2020-06",
                  "release_date_precision": "month"
                },
                {
                  "id": "yearEpisode",
                  "name": "Year Episode",
                  "duration_ms": 60000,
                  "release_date": "2019",
                  "release_date_precision": "year"
                }
              ],
              "next": null
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(pageResponse);

    // act
    var episodes = client.findShowEpisodes("testShowId");

    // assert
    assertThat(episodes).hasSize(3);
    assertThat(episodes.get(0).releaseDate()).isEqualTo(LocalDate.of(2021, 3, 28));
    assertThat(episodes.get(1).releaseDate()).isEqualTo(LocalDate.of(2020, 6, 1));
    assertThat(episodes.get(2).releaseDate()).isEqualTo(LocalDate.of(2019, 1, 1));
  }

  @Test
  void findShowEpisodesShouldThrowWhenRequestFails() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var pageResponse =
        createMockResponse(404, "{\"error\":{\"status\":404,\"message\":\"Not found.\"}}");

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(pageResponse);

    // act & assert
    assertThatThrownBy(() -> client.findShowEpisodes("nonexistentShow"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IOException.class)
        .hasMessageContaining("Spotify API request failed with status code 404");
  }

  @Test
  void findShowEpisodesShouldSkipNullItemsInResponse() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var pageResponse =
        createMockResponse(
            200,
            """
            {
              "items": [
                {
                  "id": "episode1",
                  "name": "Episode 1",
                  "duration_ms": 60000,
                  "release_date": "2021-03-28",
                  "release_date_precision": "day"
                },
                null,
                {
                  "id": "episode3",
                  "name": "Episode 3",
                  "duration_ms": 90000,
                  "release_date": "2021-05-10",
                  "release_date_precision": "day"
                }
              ],
              "next": null
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(pageResponse);

    // act
    var episodes = client.findShowEpisodes("testShowId");

    // assert
    assertThat(episodes).hasSize(2);
    assertThat(episodes.get(0).id()).isEqualTo("episode1");
    assertThat(episodes.get(1).id()).isEqualTo("episode3");
  }

  @Test
  void findShowEpisodesShouldThrowWhenItemReleaseDateIsNull() throws Exception {
    // arrange
    seedSpotifySecret();

    var tokenResponse = createMockResponse(200, TOKEN_RESPONSE);
    var pageResponse =
        createMockResponse(
            200,
            """
            {
              "items": [
                {
                  "id": "episode1",
                  "name": "Episode 1",
                  "duration_ms": 60000,
                  "release_date": null,
                  "release_date_precision": "day"
                }
              ],
              "next": null
            }
            """);

    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(tokenResponse)
        .thenReturn(pageResponse);

    // act & assert
    assertThatThrownBy(() -> client.findShowEpisodes("testShowId"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(NullPointerException.class)
        .hasMessageContaining("Show episode release date is null");
  }

  @Test
  void parseReleaseDateShouldHandleAllPrecisions() {
    assertThat(HttpSpotifyClient.parseReleaseDate("2021-03-28", "day"))
        .isEqualTo(LocalDate.of(2021, 3, 28));
    assertThat(HttpSpotifyClient.parseReleaseDate("2021-03", "month"))
        .isEqualTo(LocalDate.of(2021, 3, 1));
    assertThat(HttpSpotifyClient.parseReleaseDate("2021", "year"))
        .isEqualTo(LocalDate.of(2021, 1, 1));
  }

  @Test
  void parseReleaseDateShouldThrowOnUnknownPrecision() {
    assertThatThrownBy(() -> HttpSpotifyClient.parseReleaseDate("2021-03-28", "decade"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown release_date_precision: decade");
  }

  @Test
  void parseReleaseDateShouldThrowOnMalformedValue() {
    assertThatThrownBy(() -> HttpSpotifyClient.parseReleaseDate("not-a-date", "day"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid release_date 'not-a-date' for precision 'day'");
  }

  private void seedSpotifySecret() throws Exception {
    var secretJson =
        objectMapper
            .createObjectNode()
            .put("spotify_client_id", "testClientId")
            .put("spotify_client_secret", "testClientSecret");
    ((FakeSecrets) secrets)
        .set("immersion_tracker_api", objectMapper.writeValueAsString(secretJson));
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> createMockResponse(int statusCode, String body) {
    HttpResponse<String> mockResponse = mock(HttpResponse.class);
    when(mockResponse.statusCode()).thenReturn(statusCode);
    when(mockResponse.body()).thenReturn(body);
    return mockResponse;
  }
}
