package com.jordansimsmith.tcginventory.fetchtcg.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

  public CardsPage findCards(
      String gameId, int setId, String cardName, String finish, int limit, int pageOffset)
      throws ServiceException {
    var request =
        HttpRequest.newBuilder()
            .uri(
                baseUri.resolve(
                    "/v3/cards?gameIds="
                        + encodeQueryParameter(gameId)
                        + "&sets="
                        + setId
                        + "&cardName="
                        + encodeQueryParameter(cardName)
                        + "&finishes="
                        + encodeQueryParameter(finish)
                        + "&pageSize="
                        + limit
                        + "&pageOffset="
                        + pageOffset))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build();
    var body = sendRequest(request, "card search");

    ProviderCardsWrapper response;
    try {
      response = objectMapper.readValue(body, ProviderCardsWrapper.class);
    } catch (IOException e) {
      throw new IllegalStateException("FetchTCG returned malformed card search results");
    }
    if (response == null || response.searchResults() == null) {
      throw new IllegalStateException("FetchTCG card search did not include a page");
    }
    var page = response.searchResults();
    return new CardsPage(page.number(), page.size(), page.last(), page.content());
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

  public OffersPage findOffers(String bearerToken, int limit, int page) throws ServiceException {
    var request =
        HttpRequest.newBuilder()
            .uri(
                baseUri.resolve(
                    "/v2/private/market/offers/seller?sort=NEWEST&size=" + limit + "&page=" + page))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .GET()
            .build();
    var body = sendRequest(request, "seller offers lookup");

    ProviderOffersPage response;
    try {
      response = objectMapper.readValue(body, ProviderOffersPage.class);
    } catch (IOException e) {
      throw new IllegalStateException("FetchTCG returned malformed seller offers");
    }
    if (response == null) {
      throw new IllegalStateException("FetchTCG seller offers response did not include a page");
    }
    return new OffersPage(response.number(), response.size(), response.last(), response.content());
  }

  public UpdateListingResponse updateListing(String bearerToken, UpdateListingRequest request)
      throws ServiceException {
    var requestBody = new LinkedHashMap<String, Object>();
    requestBody.put("cardId", request.cardId());
    requestBody.put("condition", request.condition());
    requestBody.put("listedPrice", request.price());
    requestBody.put("listedCurrency", "NZD");
    requestBody.put("matchPriceEnabled", false);
    requestBody.put("quantity", request.quantity());
    requestBody.put("details", "");
    if (request.frontImage() != null) {
      requestBody.put("frontImage", request.frontImage());
    }
    if (request.additionalImages() != null) {
      requestBody.put(
          "additionalImages",
          request.additionalImages().stream()
              .map(url -> new ProviderAdditionalImage(null, url))
              .toList());
    }

    String payload;
    try {
      payload = objectMapper.writeValueAsString(requestBody);
    } catch (IOException e) {
      throw new IllegalStateException("FetchTCG listing update request could not be serialized");
    }
    var httpRequest =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v2/private/manage-listings"))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
    var body = sendRequest(httpRequest, "listing update");

    ProviderUpdateListingResponse response;
    try {
      response = objectMapper.readValue(body, ProviderUpdateListingResponse.class);
    } catch (IOException e) {
      throw new IllegalStateException("FetchTCG returned a malformed listing update response");
    }
    if (response == null || response.listingId() == null || response.remainingQuantity() == null) {
      throw new IllegalStateException("FetchTCG listing update response was incomplete");
    }
    return new UpdateListingResponse(response.listingId(), response.remainingQuantity());
  }

  public void deleteListing(String bearerToken, int listingId) throws ServiceException {
    var request =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v1/manage-listings/" + listingId))
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer " + bearerToken)
            .DELETE()
            .build();
    try {
      sendRequest(request, "listing deletion");
    } catch (NotFoundException e) {
      // deletion is complete when the listing is already absent
    }
  }

  public String createListingImage(String bearerToken, byte[] imageBytes, String filename)
      throws ServiceException {
    var boundary = UUID.randomUUID().toString();
    var request =
        HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v2/private/manage-listings/uploadListingImage"))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Authorization", "Bearer " + bearerToken)
            .POST(
                HttpRequest.BodyPublishers.ofByteArray(
                    encodeMultipart(imageBytes, filename, boundary)))
            .build();
    var body = sendRequest(request, "image upload");

    ProviderImageUploadResponse response;
    try {
      response = objectMapper.readValue(body, ProviderImageUploadResponse.class);
    } catch (IOException e) {
      throw new IllegalStateException("FetchTCG returned a malformed image upload response");
    }
    if (response == null || response.imageUrl() == null || response.imageUrl().isBlank()) {
      throw new IllegalStateException(
          "FetchTCG image upload response did not include an image URL");
    }
    return response.imageUrl();
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

  private static String encodeQueryParameter(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  public record GetCardResponse(
      String id, String name, BigDecimal marketPrice, Map<String, String> externalReferences) {}

  public record ListingsPage(
      Integer number, Integer size, Boolean last, List<ProviderCardListing> content) {}

  public record CardsPage(
      Integer number, Integer size, Boolean last, List<ProviderCardSummary> content) {}

  public record OffersPage(
      Integer number, Integer size, Boolean last, List<ProviderOffer> content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ProviderAdditionalImage(
      @JsonProperty("label") @Nullable String label, @JsonProperty("url") String url) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ProviderUpdateListingResponse(
      @JsonProperty("listingId") @Nullable Integer listingId,
      @JsonProperty("remainingQuantity") @Nullable Integer remainingQuantity) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ProviderImageUploadResponse(@JsonProperty("imageUrl") String imageUrl) {}

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
  private record ProviderCardsWrapper(
      @JsonProperty("searchResults") @Nullable ProviderCardsPage searchResults) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ProviderCardsPage(
      @JsonProperty("number") @Nullable Integer number,
      @JsonProperty("size") @Nullable Integer size,
      @JsonProperty("last") @Nullable Boolean last,
      @JsonProperty("content") @Nullable List<ProviderCardSummary> content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ProviderCardSummary(@JsonProperty("id") @Nullable String id) {}

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
  public record ProviderOffer(
      @JsonProperty("id") @Nullable Long id,
      @JsonProperty("status") @Nullable String status,
      @JsonProperty("currentAction") @Nullable String currentAction,
      @JsonProperty("acceptedAt") @Nullable String acceptedAt,
      @JsonProperty("deliveryMode") @Nullable String deliveryMode,
      @JsonProperty("buyerName") @Nullable String buyerName,
      @JsonProperty("buyerRegionAddress") @Nullable ProviderBuyerAddress buyerRegionAddress,
      @JsonProperty("shippingOption") @Nullable ProviderShippingOption shippingOption,
      @JsonProperty("totalOfferPrice") @Nullable BigDecimal totalOfferPrice,
      @JsonProperty("items") @Nullable List<ProviderOfferItem> items) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ProviderBuyerAddress(
      @JsonProperty("line1") @Nullable String line1,
      @JsonProperty("line2") @Nullable String line2,
      @JsonProperty("suburb") @Nullable String suburb,
      @JsonProperty("city") @Nullable String city,
      @JsonProperty("postCode") @Nullable String postCode,
      @JsonProperty("country") @Nullable String country) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ProviderShippingOption(@JsonProperty("title") @Nullable String title) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ProviderOfferItem(
      @JsonProperty("listing") @Nullable ProviderOfferListing listing,
      @JsonProperty("quantity") int quantity,
      @JsonProperty("price") @Nullable BigDecimal price) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ProviderOfferListing(
      @JsonProperty("id") @Nullable Integer id,
      @JsonProperty("condition") @Nullable String condition,
      @JsonProperty("listedPrice") @Nullable BigDecimal listedPrice) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ProviderOffersPage(
      @JsonProperty("number") @Nullable Integer number,
      @JsonProperty("size") @Nullable Integer size,
      @JsonProperty("last") @Nullable Boolean last,
      @JsonProperty("content") @Nullable List<ProviderOffer> content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GetProfileResponse(@JsonProperty("username") String username) {}
}
