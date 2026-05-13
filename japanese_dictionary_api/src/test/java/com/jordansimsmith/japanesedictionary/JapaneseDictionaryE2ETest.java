package com.jordansimsmith.japanesedictionary;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class JapaneseDictionaryE2ETest {

  @Container
  private static final JapaneseDictionaryContainer japaneseDictionaryContainer =
      new JapaneseDictionaryContainer();

  private HttpClient httpClient;
  private ObjectMapper objectMapper;
  private URI apiUrl;
  private String authHeader;

  @BeforeEach
  void setUp() {
    httpClient = HttpClient.newHttpClient();
    objectMapper = new ObjectMapper();
    apiUrl = japaneseDictionaryContainer.getApiUrl();
    authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("testuser:testpass".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void getSearchShouldReturnMatchingFixturesForKanjiPrefix()
      throws IOException, InterruptedException {
    var encoded = URLEncoder.encode("新", StandardCharsets.UTF_8);
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/search?q=" + encoded))
            .header("Authorization", authHeader)
            .GET()
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    var body = objectMapper.readValue(response.body(), SearchHandler.SearchResponse.class);
    assertThat(body.results())
        .extracting(SearchHandler.SearchResult::sequence)
        .containsExactly(3L, 2L, 1L);
    assertThat(body.results().get(0).expression()).isEqualTo("新しい");
    assertThat(body.results().get(0).reading()).isEqualTo("あたらしい");
    assertThat(body.results().get(0).readingRomaji()).isEqualTo("atarashii");
  }

  @Test
  void getSearchShouldReturnMatchingFixturesForKanaPrefix()
      throws IOException, InterruptedException {
    var encoded = URLEncoder.encode("しん", StandardCharsets.UTF_8);
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/search?q=" + encoded))
            .header("Authorization", authHeader)
            .GET()
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    var body = objectMapper.readValue(response.body(), SearchHandler.SearchResponse.class);
    assertThat(body.results())
        .extracting(SearchHandler.SearchResult::sequence)
        .containsExactly(4L, 2L, 1L, 5L);
  }

  @Test
  void getSearchShouldReturnMatchingFixturesForRomajiPrefix()
      throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/search?q=shin"))
            .header("Authorization", authHeader)
            .GET()
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    var body = objectMapper.readValue(response.body(), SearchHandler.SearchResponse.class);
    assertThat(body.results())
        .extracting(SearchHandler.SearchResult::sequence)
        .containsExactly(4L, 2L, 1L, 5L);
  }

  @Test
  void getSearchShouldReturnEmptyResultsForEmptyQ() throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/search?q="))
            .header("Authorization", authHeader)
            .GET()
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    var body = objectMapper.readValue(response.body(), SearchHandler.SearchResponse.class);
    assertThat(body.results()).isEmpty();
  }

  @Test
  void getSearchShouldRejectQLongerThan64() throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/search?q=" + "x".repeat(65)))
            .header("Authorization", authHeader)
            .GET()
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    var body = objectMapper.readValue(response.body(), SearchHandler.ErrorResponse.class);
    assertThat(body.message()).isEqualTo("q too long");
  }

  // missing-Authorization handling cannot be exercised here because LocalStack community does
  // not enforce CUSTOM API Gateway authorizers; the authorizer logic is covered exhaustively
  // in lib/auth's RequestAuthorizerTest and the wiring is asserted in the infra Terraform plan.

  @Test
  void getSearchShouldHydrateGlossaryTreeForImageBearingFixture()
      throws IOException, InterruptedException {
    var encoded = URLEncoder.encode("心", StandardCharsets.UTF_8);
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/search?q=" + encoded))
            .header("Authorization", authHeader)
            .GET()
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    var body = objectMapper.readValue(response.body(), SearchHandler.SearchResponse.class);
    assertThat(body.results()).hasSize(1);
    var image = body.results().get(0).glossaryRaw();
    assertThat(image.get("tag").asText()).isEqualTo("img");
    assertThat(image.get("path").asText()).isEqualTo("jitendex/graphics/heart.png");
  }

  @Test
  void putBookmarkThenGetBookmarksShouldReturnTheBookmarkedSequence()
      throws IOException, InterruptedException {
    var putRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/bookmarks/2"))
            .header("Authorization", authHeader)
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();
    var putResponse = httpClient.send(putRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(putResponse.statusCode()).isEqualTo(201);
    var putBody =
        objectMapper.readValue(
            putResponse.body(), CreateBookmarkHandler.CreateBookmarkResponse.class);
    assertThat(putBody.sequence()).isEqualTo(2L);
    assertThat(putBody.createdAt()).isPositive();

    var getRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/bookmarks"))
            .header("Authorization", authHeader)
            .GET()
            .build();
    var getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(getResponse.statusCode()).isEqualTo(200);
    var getBody =
        objectMapper.readValue(
            getResponse.body(), FindBookmarksHandler.FindBookmarksResponse.class);
    assertThat(getBody.sequences()).contains(2L);
  }

  @Test
  void putBookmarkShouldRejectNonNumericSequence() throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/bookmarks/abc"))
            .header("Authorization", authHeader)
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    var body = objectMapper.readValue(response.body(), CreateBookmarkHandler.ErrorResponse.class);
    assertThat(body.message()).isEqualTo("sequence must be a positive integer");
  }

  @Test
  void deleteBookmarkShouldRemoveExistingBookmark() throws IOException, InterruptedException {
    var putRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/bookmarks/3"))
            .header("Authorization", authHeader)
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();
    assertThat(httpClient.send(putRequest, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(201);

    var deleteRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/bookmarks/3"))
            .header("Authorization", authHeader)
            .DELETE()
            .build();
    var deleteResponse = httpClient.send(deleteRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(deleteResponse.statusCode()).isEqualTo(204);
    assertThat(deleteResponse.body()).isEmpty();

    var getRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/bookmarks"))
            .header("Authorization", authHeader)
            .GET()
            .build();
    var getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString());

    assertThat(getResponse.statusCode()).isEqualTo(200);
    var getBody =
        objectMapper.readValue(
            getResponse.body(), FindBookmarksHandler.FindBookmarksResponse.class);
    assertThat(getBody.sequences()).doesNotContain(3L);
  }

  @Test
  void deleteBookmarkShouldBeIdempotentWhenBookmarkDoesNotExist()
      throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/bookmarks/999999"))
            .header("Authorization", authHeader)
            .DELETE()
            .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(204);
  }
}
