package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public class HttpFetchTcgClient {
  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
          + " (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
  private static final int MAX_RETRIES = 3;

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

  public GetProfileResponse getProfile(String bearerToken) throws ServiceException {
    var request =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v1/private/profile"))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .GET()
            .build();

    var body = sendRequest(request, "profile lookup");
    GetProfileResponse response;
    try {
      response = objectMapper.readValue(body, GetProfileResponse.class);
    } catch (IOException e) {
      throw new IllegalStateException("FetchTCG returned a malformed profile response");
    }

    if (response == null || response.username() == null || response.username().isBlank()) {
      throw new IllegalStateException("FetchTCG profile did not include a username");
    }

    return response;
  }

  public GetCardResponse getCard(String cardId) throws ServiceException {
    var encodedCardId = encodePathSegment(cardId);
    var request =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v3/cards/" + encodedCardId))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build();
    var body = sendRequest(request, "card lookup");

    ProviderCardResponse response;
    try {
      response = objectMapper.readValue(body, ProviderCardResponse.class);
    } catch (IOException e) {
      throw new IllegalStateException("FetchTCG returned a malformed card response");
    }
    if (response == null || !cardId.equals(response.id())) {
      throw new IllegalStateException("FetchTCG card response did not match the requested card");
    }

    var nzPricing = response.pricingData() != null ? response.pricingData().get("NZ") : null;
    var marketPrice = nzPricing != null ? nzPricing.tcgMarketPrice() : null;
    return new GetCardResponse(
        response.id(), response.name(), marketPrice, response.externalReferences());
  }

  public ListingsPage findCardListings(String cardId, int limit, int pageOffset)
      throws ServiceException {
    var encodedCardId = encodePathSegment(cardId);
    var request =
        HttpRequest.newBuilder()
            .uri(
                baseUri.resolve(
                    "/v3/cards/"
                        + encodedCardId
                        + "/listings?countryCode=NZ&currencyCode=NZD&pageSize="
                        + limit
                        + "&pageOffset="
                        + pageOffset
                        + "&sort=PRICE_ASC"))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build();
    var body = sendRequest(request, "card listings lookup");

    ProviderListingsWrapper response;
    try {
      response = objectMapper.readValue(body, ProviderListingsWrapper.class);
    } catch (IOException e) {
      throw new IllegalStateException("FetchTCG returned malformed card listings");
    }
    if (response == null || response.searchResults() == null) {
      throw new IllegalStateException("FetchTCG listings response did not include a page");
    }

    var page = response.searchResults();
    if (page.content() != null) {
      for (var listing : page.content()) {
        if (listing.id() == null
            || listing.condition() == null
            || listing.listedPriceInRequestedCurrency() == null
            || listing.sellerProfileName() == null) {
          throw new IllegalStateException(
              "FetchTCG listings response contained an invalid listing");
        }
      }
    }
    return new ListingsPage(page.number(), page.size(), page.last(), page.content());
  }

  private String sendRequest(HttpRequest request, String operation) throws ServiceException {
    for (var attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      pacer.run();
      if (Thread.currentThread().isInterrupted()) {
        throw new ServiceUnavailableException("FetchTCG " + operation + " was interrupted");
      }

      HttpResponse<String> response;
      try {
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ServiceUnavailableException("FetchTCG " + operation + " was interrupted");
      } catch (IOException e) {
        throw new ServiceUnavailableException(
            "FetchTCG " + operation + " is temporarily unavailable");
      }

      var statusCode = response.statusCode();
      if (statusCode >= 500 && attempt < MAX_RETRIES) {
        continue;
      }
      if (statusCode >= 200 && statusCode < 300) {
        return response.body();
      }
      if (statusCode == 401 || statusCode == 403) {
        throw new FetchTcgAuthenticationException(statusCode);
      }
      if (statusCode == 404) {
        throw new NotFoundException("FetchTCG card was not found");
      }
      throw new ServiceUnavailableException(
          "FetchTCG " + operation + " is temporarily unavailable");
    }

    throw new ServiceUnavailableException("FetchTCG " + operation + " is temporarily unavailable");
  }

  private static String encodePathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  public record GetCardResponse(
      String id, String name, BigDecimal marketPrice, Map<String, String> externalReferences) {}

  public record ListingsPage(
      Integer number, Integer size, Boolean last, List<ProviderCardListing> content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ProviderCardResponse(
      @JsonProperty("id") String id,
      @JsonProperty("name") String name,
      @JsonProperty("pricingData") @Nullable Map<String, ProviderPricingData> pricingData,
      @JsonProperty("externalReferences") @Nullable Map<String, String> externalReferences) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ProviderPricingData(@JsonProperty("tcgMarketPrice") BigDecimal tcgMarketPrice) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ProviderListingsWrapper(
      @JsonProperty("searchResults") @Nullable ProviderListingsPage searchResults) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ProviderListingsPage(
      @JsonProperty("number") @Nullable Integer number,
      @JsonProperty("size") @Nullable Integer size,
      @JsonProperty("last") @Nullable Boolean last,
      @JsonProperty("content") @Nullable List<ProviderCardListing> content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ProviderCardListing(
      @JsonProperty("id") @Nullable Integer id,
      @JsonProperty("condition") @Nullable String condition,
      @JsonProperty("listedPriceInRequestedCurrency") @Nullable
          BigDecimal listedPriceInRequestedCurrency,
      @JsonProperty("sellerProfileName") @Nullable String sellerProfileName) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GetProfileResponse(@JsonProperty("username") String username) {}
}
