package com.jordansimsmith.tcginventory.fetchtcg;

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
              "id": "mtg_141_c_a25_normal",
              "name": "Lightning Bolt",
              "pricingData": {
                "NZ": {
                  "tcgMarketPrice": 1.50
                }
              },
              "externalReferences": {
                "tcgId": "161863",
                "scryfallId": "f0a51425-d796-48b8-b68c-bc21fb465c81"
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
    assertThat(result.externalReferences().get("scryfallId"))
        .isEqualTo("f0a51425-d796-48b8-b68c-bc21fb465c81");
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
    assertThatThrownBy(() -> client.getCard("mtg_1_c_dom_normal"))
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
    assertThatThrownBy(() -> client.getCard("mtg_1_c_dom_normal"))
        .isInstanceOf(FetchTcgAuthException.class);

    assertThat(pacerCallCount.get()).isEqualTo(1);
  }

  @Test
  void shouldThrowWrappedFetchTcgNotFoundExceptionOn404() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(404, "");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act & assert
    assertThatThrownBy(() -> client.getCard("mtg_1_c_dom_normal"))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(FetchTcgNotFoundException.class)
        .hasMessageContaining("404");

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
    var result = client.searchCards("mtg", 42, "Lightning Bolt", "normal");

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
    client.searchCards("mtg", 78, "Spidersilk Net", "normal");

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
  void searchCardsShouldUrlEncodeDiacriticsInCardName() throws IOException, InterruptedException {
    // arrange
    var response = createMockResponse(200, "{\"searchResults\": {\"content\": []}}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(response);

    // act
    client.searchCards("provider-game", 3268, "Troll of Khazad-dûm", "normal");

    // assert
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    assertThat(requestCaptor.getValue().uri())
        .isEqualTo(
            URI.create(
                "https://api.fetchtcg.com/v3/cards?gameIds=provider-game&sets=3268&cardName=Troll+of+Khazad-d%C3%BBm&finishes=normal"));
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> createMockResponse(int statusCode, String body) {
    HttpResponse<String> mockResponse = mock(HttpResponse.class);
    when(mockResponse.statusCode()).thenReturn(statusCode);
    when(mockResponse.body()).thenReturn(body);
    return mockResponse;
  }
}
