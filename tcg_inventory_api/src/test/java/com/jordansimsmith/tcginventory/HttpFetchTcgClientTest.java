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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
              "id": 12345,
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
    var result = client.getCard(12345);

    // assert
    assertThat(result.id()).isEqualTo(12345);
    assertThat(result.name()).isEqualTo("Lightning Bolt");
    assertThat(result.pricingData()).containsKey("NZ");
    assertThat(result.pricingData().get("NZ").tcgMarketPrice()).isEqualByComparingTo("1.50");
  }

  @Test
  void getCardShouldNotAttachBearerToken() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "{\"id\": 1, \"name\": \"x\"}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    client.getCard(1);

    // assert
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requestCaptor.getValue().headers().firstValue("Authorization")).isEmpty();
    assertThat(requestCaptor.getValue().uri())
        .isEqualTo(URI.create("https://api.fetchtcg.com/v3/cards/1"));
  }

  @Test
  void getSellerOffersShouldAttachBearerToken() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "{\"data\": [], \"totalPages\": 1}");
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
        .isEqualTo(URI.create("https://api.fetchtcg.com/v2/private/market/offers/seller?page=1"));
  }

  @Test
  void getSellerOffersShouldReturnParsedResponse() throws IOException, InterruptedException {
    // arrange
    var response =
        createMockResponse(
            200,
            """
            {
              "data": [
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
    assertThat(result.data()).hasSize(2);
    assertThat(result.data().get(0).id()).isEqualTo(100);
    assertThat(result.data().get(0).status()).isEqualTo("ACCEPTED");
    assertThat(result.data().get(0).currentAction()).isEqualTo("AWAITING_PAYMENT");
    assertThat(result.data().get(1).currentAction()).isNull();
  }

  @Test
  void shouldCallPacerBeforeEachRequest() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "{\"id\": 1, \"name\": \"x\"}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    client.getCard(1);

    // assert
    assertThat(pacerCallCount.get()).isEqualTo(1);
  }

  @Test
  void shouldCallPacerOnEachRetry() throws IOException, InterruptedException {
    // arrange
    var failResponse = createMockResponse(500, "Internal Server Error");
    var successResponse = createMockResponse(200, "{\"id\": 1, \"name\": \"x\"}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(failResponse)
        .thenReturn(failResponse)
        .thenReturn(successResponse);

    // act
    var result = client.getCard(1);

    // assert
    assertThat(result.id()).isEqualTo(1);
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
    assertThatThrownBy(() -> client.getCard(1))
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
    assertThatThrownBy(() -> client.getCard(1))
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
    var response = createMockResponse(200, "{\"id\": 1, \"name\": \"x\"}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    client.getCard(1);

    // assert
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requestCaptor.getValue().headers().firstValue("User-Agent"))
        .contains(HttpFetchTcgClient.USER_AGENT);
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> createMockResponse(int statusCode, String body) {
    HttpResponse<String> mockResponse = mock(HttpResponse.class);
    when(mockResponse.statusCode()).thenReturn(statusCode);
    when(mockResponse.body()).thenReturn(body);
    return mockResponse;
  }
}
