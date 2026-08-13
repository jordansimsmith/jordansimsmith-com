package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

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
  public SearchCardsResponse searchCards(int setId, String cardName, String finish) {
    try {
      return doSearchCards(setId, cardName, finish);
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
  public GetCardListingsResponse getCardListings(String cardId) {
    try {
      return doGetCardListings(cardId);
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
  public GetSellerOffersResponse getSellerOffers(String bearerToken, int page) {
    try {
      return doGetSellerOffers(bearerToken, page);
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

  private SearchCardsResponse doSearchCards(int setId, String cardName, String finish)
      throws IOException, InterruptedException {
    var encodedName = cardName.replace(" ", "+");
    var request =
        HttpRequest.newBuilder()
            .uri(
                baseUri.resolve(
                    "/v3/cards?gameIds=mtg&sets="
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

  private GetCardListingsResponse doGetCardListings(String cardId)
      throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder()
            .uri(
                baseUri.resolve(
                    "/v3/cards/" + cardId + "/listings?countryCode=NZ&currencyCode=NZD"))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build();

    var body = doExecute(request);
    var wrapper = objectMapper.readValue(body, ListingsResultsWrapper.class);
    var content =
        wrapper.searchResults() != null
            ? wrapper.searchResults().content()
            : List.<CardListing>of();
    return new GetCardListingsResponse(content);
  }

  private GetSellerOffersResponse doGetSellerOffers(String bearerToken, int page)
      throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder()
            .uri(
                baseUri.resolve(
                    "/v2/private/market/offers/seller?sort=NEWEST&size=20&page=" + page))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .GET()
            .build();

    var body = doExecute(request);
    return objectMapper.readValue(body, GetSellerOffersResponse.class);
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
  record ListingsResultsWrapper(
      @JsonProperty("searchResults") PagedContent<CardListing> searchResults) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record PagedContent<T>(@JsonProperty("content") List<T> content) {}
}
