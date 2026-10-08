package com.jordansimsmith.tcginventory.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;

public class HttpTcgCsvClient implements TcgCsvClient {
  private static final int MAX_RETRIES = 3;
  private static final String USER_AGENT =
      "TcgInventory/1.0 (https://tcg-inventory.jordansimsmith.com)";
  private static final Pattern COMPACT_OFFSET = Pattern.compile("([+-]\\d{2})(\\d{2})$");

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record CollectionResponse<T>(
      @JsonProperty("success") Boolean success,
      @JsonProperty("errors") List<String> errors,
      @JsonProperty("totalItems") Integer totalItems,
      @JsonProperty("results") List<T> results) {}

  private final URI baseUri;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Runnable pacer;

  public HttpTcgCsvClient(
      URI baseUri, HttpClient httpClient, ObjectMapper objectMapper, Runnable pacer) {
    this.baseUri = baseUri;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.pacer = pacer;
  }

  @Override
  public UpdateMarker getUpdateMarker() throws IOException, InterruptedException {
    var sourceMarker = getText("/last-updated.txt").trim();
    if (sourceMarker.isEmpty()) {
      throw new IOException("TCGCSV update marker is empty");
    }
    var normalizedMarker = COMPACT_OFFSET.matcher(sourceMarker).replaceFirst("$1:$2");
    try {
      return new UpdateMarker(sourceMarker, OffsetDateTime.parse(normalizedMarker).toInstant());
    } catch (RuntimeException e) {
      throw new IOException("TCGCSV update marker is not an offset timestamp", e);
    }
  }

  @Override
  public List<Group> findGroups(int categoryId) throws IOException, InterruptedException {
    return getCollection("/tcgplayer/%d/groups".formatted(categoryId), Group.class);
  }

  @Override
  public List<Product> findProducts(int categoryId, int groupId)
      throws IOException, InterruptedException {
    return getCollection("/tcgplayer/%d/%d/products".formatted(categoryId, groupId), Product.class);
  }

  @Override
  public List<Price> findPrices(int categoryId, int groupId)
      throws IOException, InterruptedException {
    return getCollection("/tcgplayer/%d/%d/prices".formatted(categoryId, groupId), Price.class);
  }

  private <T> List<T> getCollection(String path, Class<T> recordClass)
      throws IOException, InterruptedException {
    var body = getText(path);
    var responseType =
        objectMapper
            .getTypeFactory()
            .constructParametricType(CollectionResponse.class, recordClass);
    CollectionResponse<T> response = objectMapper.readValue(body, responseType);
    if (!Boolean.TRUE.equals(response.success())
        || response.errors() == null
        || !response.errors().isEmpty()
        || response.results() == null) {
      throw new IOException("TCGCSV returned an unsuccessful collection: " + path);
    }
    if (response.totalItems() != null && response.totalItems() != response.results().size()) {
      throw new IOException("TCGCSV collection count does not match results: " + path);
    }
    return List.copyOf(response.results());
  }

  private synchronized String getText(String path) throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json, text/plain")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();

    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      pacer.run();
      HttpResponse<String> response;
      try {
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (IOException e) {
        if (attempt == MAX_RETRIES) {
          throw new IOException(
              "TCGCSV request failed after " + (attempt + 1) + " attempts: " + path, e);
        }
        continue;
      }

      if (response.statusCode() == 200) {
        return response.body();
      }
      if (response.statusCode() < 500 || attempt == MAX_RETRIES) {
        throw new IOException(
            "TCGCSV request failed with status code "
                + response.statusCode()
                + " after "
                + (attempt + 1)
                + " attempt(s): "
                + path);
      }
    }

    throw new IllegalStateException("TCGCSV request retry loop ended unexpectedly: " + path);
  }
}
