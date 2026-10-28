package com.jordansimsmith.tcginventory.fetchtcg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public class HttpFetchTcgClient implements FetchTcgClient {
  static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
          + " (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
  static final int MAX_RETRIES = 3;

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

  @Override
  public GetCardResponse getCard(String cardId) {
    try {
      return doGetCard(cardId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (FetchTcgAuthException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public SearchCardsResponse searchCards(
      String fetchTcgGameId, int setId, String cardName, String finish) {
    try {
      return doSearchCards(fetchTcgGameId, setId, cardName, finish);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (FetchTcgAuthException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String uploadListingImage(String bearerToken, byte[] bytes, String filename) {
    try {
      return doUploadListingImage(bearerToken, bytes, filename);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (FetchTcgAuthException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private GetCardResponse doGetCard(String cardId) throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v3/cards/" + cardId))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build();

    var body = doExecute(request);
    return objectMapper.readValue(body, GetCardResponse.class);
  }

  private SearchCardsResponse doSearchCards(
      String fetchTcgGameId, int setId, String cardName, String finish)
      throws IOException, InterruptedException {
    var encodedName = URLEncoder.encode(cardName, StandardCharsets.UTF_8);
    var encodedGameId = URLEncoder.encode(fetchTcgGameId, StandardCharsets.UTF_8);
    var request =
        HttpRequest.newBuilder()
            .uri(
                baseUri.resolve(
                    "/v3/cards?gameIds="
                        + encodedGameId
                        + "&sets="
                        + setId
                        + "&cardName="
                        + encodedName
                        + "&finishes="
                        + finish))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build();

    var body = doExecute(request);
    var wrapper = objectMapper.readValue(body, SearchResultsWrapper.class);
    var content =
        wrapper.searchResults() != null ? wrapper.searchResults().content() : List.<SearchCard>of();
    return new SearchCardsResponse(content);
  }

  private String doUploadListingImage(String bearerToken, byte[] bytes, String filename)
      throws IOException, InterruptedException {
    var boundary = UUID.randomUUID().toString();
    var request =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v2/private/manage-listings/uploadListingImage"))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Authorization", "Bearer " + bearerToken)
            .POST(
                HttpRequest.BodyPublishers.ofByteArray(encodeMultipart(bytes, filename, boundary)))
            .build();

    var body = doExecute(request);
    return objectMapper.readValue(body, UploadListingImageResponse.class).imageUrl();
  }

  private static byte[] encodeMultipart(byte[] fileBytes, String filename, String boundary) {
    var header =
        "--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\""
            + filename
            + "\"\r\n"
            + "Content-Type: image/jpeg\r\n"
            + "\r\n";
    var footer = "\r\n--" + boundary + "--\r\n";
    var headerBytes = header.getBytes(StandardCharsets.UTF_8);
    var footerBytes = footer.getBytes(StandardCharsets.UTF_8);
    var body = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
    System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
    System.arraycopy(fileBytes, 0, body, headerBytes.length, fileBytes.length);
    System.arraycopy(
        footerBytes, 0, body, headerBytes.length + fileBytes.length, footerBytes.length);
    return body;
  }

  private String doExecute(HttpRequest request) throws IOException, InterruptedException {
    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      pacer.run();

      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      var statusCode = response.statusCode();

      if (statusCode >= 200 && statusCode < 300) {
        return response.body();
      }

      if (statusCode == 401 || statusCode == 403) {
        throw new FetchTcgAuthException(
            statusCode, "FetchTCG authentication failed with status " + statusCode);
      }

      if (statusCode == 404) {
        throw new FetchTcgNotFoundException(
            "FetchTCG request failed with status 404: " + response.body());
      }

      if (statusCode >= 500 && attempt < MAX_RETRIES) {
        continue;
      }

      throw new IOException(
          "FetchTCG request failed with status "
              + statusCode
              + " after "
              + (attempt + 1)
              + " attempt(s): "
              + response.body());
    }

    throw new IOException("FetchTCG request failed after " + (MAX_RETRIES + 1) + " attempts");
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SearchResultsWrapper(
      @JsonProperty("searchResults") PagedContent<SearchCard> searchResults) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record PagedContent<T>(@JsonProperty("content") List<T> content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record UploadListingImageResponse(@JsonProperty("imageUrl") String imageUrl) {}
}
