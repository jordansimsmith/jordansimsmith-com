package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

public class HttpFetchTcgClientTest {
  @Mock HttpClient httpClient;

  private ObjectMapper objectMapper;
  private AtomicInteger pacerCallCount;
  private HttpFetchTcgClient client;
  private AutoCloseable openMocks;

  @BeforeEach
  void setUp() {
    openMocks = openMocks(this);
    objectMapper = new ObjectMapper();
    pacerCallCount = new AtomicInteger(0);
    client =
        new HttpFetchTcgClient(
            URI.create("https://api.fetchtcg.com"),
            httpClient,
            objectMapper,
            pacerCallCount::incrementAndGet);
  }

  @AfterEach
  void tearDown() throws Exception {
    openMocks.close();
  }

  @Test
  void getCardShouldReturnParsedResponse() throws IOException, InterruptedException {
    // arrange
    var response =
        createMockResponse(
            200,
            """
            {
              "id": "mtg_141_c_a25_normal",
              "name": "Lightning Bolt",
              "pricingData": {
                "NZ": {
                  "tcgMarketPrice": 1.50
                }
              }
            }
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    var result = client.getCard("mtg_141_c_a25_normal");

    // assert
    assertThat(result.id()).isEqualTo("mtg_141_c_a25_normal");
    assertThat(result.name()).isEqualTo("Lightning Bolt");
    assertThat(result.pricingData()).containsKey("NZ");
    assertThat(result.pricingData().get("NZ").tcgMarketPrice()).isEqualByComparingTo("1.50");
  }

  @Test
  void getCardShouldConstructCorrectUri() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "{\"id\": \"mtg_141_c_a25_normal\", \"name\": \"x\"}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    client.getCard("mtg_141_c_a25_normal");

    // assert
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requestCaptor.getValue().headers().firstValue("Authorization")).isEmpty();
    assertThat(requestCaptor.getValue().uri())
        .isEqualTo(URI.create("https://api.fetchtcg.com/v3/cards/mtg_141_c_a25_normal"));
  }

  @Test
  void getSellerOffersShouldAttachBearerToken() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "{\"content\": [], \"totalPages\": 1}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    client.getSellerOffers("my-bearer-token", 1);

    // assert
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requestCaptor.getValue().headers().firstValue("Authorization"))
        .contains("Bearer my-bearer-token");
    assertThat(requestCaptor.getValue().uri())
        .isEqualTo(
            URI.create(
                "https://api.fetchtcg.com/v2/private/market/offers/seller?sort=NEWEST&size=20&page=1"));
  }

  @Test
  void getSellerOffersShouldReturnParsedResponse() throws IOException, InterruptedException {
    // arrange
    var response =
        createMockResponse(
            200,
            """
            {
              "content": [
                {"id": 100, "status": "ACCEPTED", "currentAction": "AWAITING_PAYMENT"},
                {"id": 101, "status": "COMPLETED", "currentAction": null}
              ],
              "totalPages": 3
            }
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    var result = client.getSellerOffers("token", 1);

    // assert
    assertThat(result.totalPages()).isEqualTo(3);
    assertThat(result.content()).hasSize(2);
    assertThat(result.content().get(0).id()).isEqualTo(100);
    assertThat(result.content().get(0).status()).isEqualTo("ACCEPTED");
    assertThat(result.content().get(0).currentAction()).isEqualTo("AWAITING_PAYMENT");
    assertThat(result.content().get(1).currentAction()).isNull();
  }

  @Test
  void shouldCallPacerBeforeEachRequest() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "{\"id\": \"mtg_1_c_dom_normal\", \"name\": \"x\"}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    client.getCard("mtg_1_c_dom_normal");

    // assert
    assertThat(pacerCallCount.get()).isEqualTo(1);
  }

  @Test
  void shouldCallPacerOnEachRetry() throws IOException, InterruptedException {
    // arrange
    var failResponse = createMockResponse(500, "Internal Server Error");
    var successResponse =
        createMockResponse(200, "{\"id\": \"mtg_1_c_dom_normal\", \"name\": \"x\"}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(failResponse)
        .thenReturn(failResponse)
        .thenReturn(successResponse);

    // act
    var result = client.getCard("mtg_1_c_dom_normal");

    // assert
    assertThat(result.id()).isEqualTo("mtg_1_c_dom_normal");
    assertThat(pacerCallCount.get()).isEqualTo(3);
    verify(httpClient, times(3))
        .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void shouldThrowAfterMaxRetriesExhausted() throws IOException, InterruptedException {
    // arrange
    var failResponse = createMockResponse(500, "Internal Server Error");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(failResponse);

    // act & assert
    assertThatThrownBy(() -> client.getCard("mtg_1_c_dom_normal"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IOException.class)
        .hasMessageContaining("500");

    assertThat(pacerCallCount.get()).isEqualTo(HttpFetchTcgClient.MAX_RETRIES + 1);
  }

  @Test
  void shouldThrowFetchTcgAuthExceptionOn401() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(401, "Unauthorized");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act & assert
    assertThatThrownBy(() -> client.getSellerOffers("bad-token", 1))
        .isInstanceOf(FetchTcgAuthException.class)
        .hasMessageContaining("401");

    verify(httpClient, times(1))
        .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void shouldThrowFetchTcgAuthExceptionOn403() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(403, "Forbidden");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act & assert
    assertThatThrownBy(() -> client.getCard("mtg_1_c_dom_normal"))
        .isInstanceOf(FetchTcgAuthException.class)
        .hasMessageContaining("403");

    verify(httpClient, times(1))
        .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void shouldNotRetryOn401() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(401, "Unauthorized");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act & assert
    assertThatThrownBy(() -> client.getSellerOffers("expired", 1))
        .isInstanceOf(FetchTcgAuthException.class);

    assertThat(pacerCallCount.get()).isEqualTo(1);
  }

  @Test
  void shouldIncludeUserAgentHeader() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "{\"id\": \"mtg_1_c_dom_normal\", \"name\": \"x\"}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    client.getCard("mtg_1_c_dom_normal");

    // assert
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requestCaptor.getValue().headers().firstValue("User-Agent"))
        .contains(HttpFetchTcgClient.USER_AGENT);
  }

  @Test
  void searchCardsShouldReturnParsedResponse() throws IOException, InterruptedException {
    // arrange
    var response =
        createMockResponse(
            200,
            """
            {
              "searchResults": {
                "content": [
                  {"id": "mtg_141_c_dom_normal"}
                ]
              }
            }
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    var result = client.searchCards(42, "Lightning Bolt", "normal");

    // assert
    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).id()).isEqualTo("mtg_141_c_dom_normal");
  }

  @Test
  void searchCardsShouldConstructCorrectUri() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "{\"searchResults\": {\"content\": []}}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    client.searchCards(78, "Spidersilk Net", "normal");

    // assert
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requestCaptor.getValue().uri())
        .isEqualTo(
            URI.create(
                "https://api.fetchtcg.com/v3/cards?gameIds=mtg&sets=78&cardName=Spidersilk+Net&finishes=normal"));
    assertThat(requestCaptor.getValue().headers().firstValue("Authorization")).isEmpty();
  }

  @Test
  void getCardListingsShouldReturnParsedResponse() throws IOException, InterruptedException {
    // arrange
    var response =
        createMockResponse(
            200,
            """
            {
              "searchResults": {
                "content": [
                  {"id": 1001, "condition": "raw-nm", "listedPrice": 2.50, "sellerProfileName": "seller1"},
                  {"id": 1002, "condition": "raw-lp", "listedPrice": 1.80, "sellerProfileName": "seller2"}
                ]
              }
            }
            """);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    var result = client.getCardListings("mtg_218_c_kld_normal");

    // assert
    assertThat(result.content()).hasSize(2);
    assertThat(result.content().get(0).id()).isEqualTo(1001);
    assertThat(result.content().get(0).condition()).isEqualTo("raw-nm");
    assertThat(result.content().get(0).listedPrice()).isEqualByComparingTo("2.50");
    assertThat(result.content().get(0).sellerProfileName()).isEqualTo("seller1");
    assertThat(result.content().get(1).condition()).isEqualTo("raw-lp");
  }

  @Test
  void getCardListingsShouldConstructCorrectUri() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "{\"searchResults\": {\"content\": []}}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    client.getCardListings("mtg_218_c_kld_normal");

    // assert
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requestCaptor.getValue().uri())
        .isEqualTo(
            URI.create(
                "https://api.fetchtcg.com/v3/cards/mtg_218_c_kld_normal/listings?countryCode=NZ&currencyCode=NZD"));
    assertThat(requestCaptor.getValue().headers().firstValue("Authorization")).isEmpty();
  }

  @Test
  void upsertListingShouldPostWithCorrectBody() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "{\"listingId\": 992552, \"remainingQuantity\": 2}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    var request =
        new FetchTcgClient.UpsertListingRequest(
            "mtg_218_c_kld_normal", "raw-nm", 2, new BigDecimal("1.50"), null, null);

    // act
    var result = client.upsertListing("my-token", request);

    // assert
    assertThat(result.listingId()).isEqualTo(992552);
    assertThat(result.remainingQuantity()).isEqualTo(2);

    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requestCaptor.getValue().uri())
        .isEqualTo(URI.create("https://api.fetchtcg.com/v2/private/manage-listings"));
    assertThat(requestCaptor.getValue().headers().firstValue("Authorization"))
        .contains("Bearer my-token");
    assertThat(requestCaptor.getValue().method()).isEqualTo("POST");

    var body = objectMapper.readTree(requestBodyString(requestCaptor.getValue()));
    assertThat(body.get("cardId").asText()).isEqualTo("mtg_218_c_kld_normal");
    assertThat(body.get("condition").asText()).isEqualTo("raw-nm");
    assertThat(body.get("listedPrice").decimalValue()).isEqualByComparingTo("1.50");
    assertThat(body.get("listedCurrency").asText()).isEqualTo("NZD");
    assertThat(body.get("matchPriceEnabled").asBoolean()).isFalse();
    assertThat(body.get("quantity").asInt()).isEqualTo(2);
    assertThat(body.get("details").asText()).isEmpty();
    assertThat(body.has("frontImage")).isFalse();
    assertThat(body.has("additionalImages")).isFalse();
  }

  @Test
  void upsertListingShouldOmitImageFieldsWhenNull() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "{\"listingId\": 1, \"remainingQuantity\": 1}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);
    var request =
        new FetchTcgClient.UpsertListingRequest(
            "mtg_218_c_kld_normal", "raw-nm", 1, new BigDecimal("1.50"), null, null);

    // act
    client.upsertListing("my-token", request);

    // assert
    var body = capturedJsonBody();
    assertThat(body.has("frontImage")).isFalse();
    assertThat(body.has("additionalImages")).isFalse();
  }

  @Test
  void upsertListingShouldSendEmptyAdditionalImagesAsEmptyArray()
      throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "{\"listingId\": 1, \"remainingQuantity\": 1}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);
    var request =
        new FetchTcgClient.UpsertListingRequest(
            "mtg_218_c_kld_normal", "raw-nm", 1, new BigDecimal("1.50"), null, List.of());

    // act
    client.upsertListing("my-token", request);

    // assert
    var body = capturedJsonBody();
    assertThat(body.has("frontImage")).isFalse();
    assertThat(body.has("additionalImages")).isTrue();
    assertThat(body.get("additionalImages").isArray()).isTrue();
    assertThat(body.get("additionalImages")).isEmpty();
  }

  @Test
  void upsertListingShouldSerializeAdditionalImagesAsLabeledObjects()
      throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "{\"listingId\": 1, \"remainingQuantity\": 1}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);
    var front = "https://listing-img.fetchtcg.com/acct/listing/front.jpg";
    var extra = "https://listing-img.fetchtcg.com/acct/listing/extra.jpg";
    var request =
        new FetchTcgClient.UpsertListingRequest(
            "mtg_218_c_kld_normal", "raw-nm", 1, new BigDecimal("1.50"), front, List.of(extra));

    // act
    client.upsertListing("my-token", request);

    // assert
    var body = capturedJsonBody();
    assertThat(body.get("frontImage").asText()).isEqualTo(front);
    assertThat(body.get("additionalImages")).hasSize(1);
    var image = body.get("additionalImages").get(0);
    assertThat(image.isObject()).isTrue();
    assertThat(image.get("label").isNull()).isTrue();
    assertThat(image.get("url").asText()).isEqualTo(extra);
    assertThat(image.get("url").isTextual()).isTrue();
  }

  @Test
  void uploadListingImageShouldPostMultipartFileAndReturnImageUrl()
      throws IOException, InterruptedException {
    // arrange
    var imageUrl = "https://listing-img.fetchtcg.com/acct/listing/5314d615.jpg";
    var response = createMockResponse(200, "{\"imageUrl\": \"" + imageUrl + "\"}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);
    var bytes = new byte[] {(byte) 0xff, (byte) 0xd8, 0x00, 0x01, 0x02};

    // act
    var result = client.uploadListingImage("my-token", bytes, "photo.jpg");

    // assert
    assertThat(result).isEqualTo(imageUrl);

    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    var request = requestCaptor.getValue();
    assertThat(request.uri())
        .isEqualTo(
            URI.create("https://api.fetchtcg.com/v2/private/manage-listings/uploadListingImage"));
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.headers().firstValue("Authorization")).contains("Bearer my-token");
    assertThat(request.headers().firstValue("User-Agent")).contains(HttpFetchTcgClient.USER_AGENT);

    var contentType = request.headers().firstValue("Content-Type").orElseThrow();
    assertThat(contentType).startsWith("multipart/form-data; boundary=");
    var boundary = contentType.substring("multipart/form-data; boundary=".length());
    assertThat(boundary).isNotBlank();

    var body = requestBodyBytes(request);
    var bodyText = new String(body, StandardCharsets.ISO_8859_1);
    assertThat(bodyText).contains("--" + boundary);
    assertThat(bodyText)
        .contains("Content-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"");
    assertThat(bodyText).contains("Content-Type: image/jpeg");
    assertThat(body).contains(bytes);
    assertThat(bodyText).endsWith("--" + boundary + "--\r\n");
  }

  @Test
  void deleteListingShouldSendDeleteRequest() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    client.deleteListing("my-token", 975737);

    // assert
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requestCaptor.getValue().uri())
        .isEqualTo(URI.create("https://api.fetchtcg.com/v1/manage-listings/975737"));
    assertThat(requestCaptor.getValue().headers().firstValue("Authorization"))
        .contains("Bearer my-token");
    assertThat(requestCaptor.getValue().method()).isEqualTo("DELETE");
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> createMockResponse(int statusCode, String body) {
    HttpResponse<String> mockResponse = mock(HttpResponse.class);
    when(mockResponse.statusCode()).thenReturn(statusCode);
    when(mockResponse.body()).thenReturn(body);
    return mockResponse;
  }

  private JsonNode capturedJsonBody() throws IOException, InterruptedException {
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    return objectMapper.readTree(requestBodyString(requestCaptor.getValue()));
  }

  private String requestBodyString(HttpRequest request) {
    return new String(requestBodyBytes(request), StandardCharsets.UTF_8);
  }

  private byte[] requestBodyBytes(HttpRequest request) {
    var publisher = request.bodyPublisher().orElseThrow();
    var subscriber = HttpResponse.BodySubscribers.ofByteArray();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscriber.onSubscribe(subscription);
          }

          @Override
          public void onNext(ByteBuffer item) {
            subscriber.onNext(List.of(item));
          }

          @Override
          public void onError(Throwable throwable) {
            subscriber.onError(throwable);
          }

          @Override
          public void onComplete() {
            subscriber.onComplete();
          }
        });
    return subscriber.getBody().toCompletableFuture().join();
  }
}
