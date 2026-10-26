package com.jordansimsmith.tcginventory.fetchtcg.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.FakeSecrets;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindOffersRequest;
import com.jordansimsmith.time.FakeClock;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class FindOffersServiceTest {
  private static final RequestContext CTX = new RequestContext("alice");
  private static final String FIREBASE_URI = "https://firebase.example/token";
  private static final String FETCH_TCG_URI = "https://api.fetchtcg.com";

  @Test
  void findOffersShouldFetchPagesUsingCachedRotatedBearerAndMapNarrowOffers()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var objectMapper = new ObjectMapper();
    var secrets = new FakeSecrets();
    secrets.set("tcg_inventory", "{\"alice\":\"refresh-one\"}");
    var clock = new FakeClock();
    clock.setTime(Instant.ofEpochSecond(1_700_000_000));
    var httpClient = mock(HttpClient.class);
    var requests = new ArrayList<HttpRequest>();
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenAnswer(
            invocation -> {
              var request = (HttpRequest) invocation.getArgument(0);
              requests.add(request);
              if (request.uri().toString().equals(FIREBASE_URI)) {
                return response(
                    200,
                    "{\"id_token\":\"bearer-one\",\"refresh_token\":\"refresh-two\",\"expires_in\":\"3600\"}");
              }
              if (request.uri().getRawQuery().endsWith("page=0")) {
                return response(
                    200,
                    """
                    {"number":0,"size":100,"last":false,"content":[{"id":5000000001,"status":"ACCEPTED","currentAction":"AWAITING_PAYMENT","acceptedAt":"2026-08-11T04:42:12.476+0000","deliveryMode":"DELIVERY","totalOfferPrice":3.33,"buyerName":"Buyer","buyerRegionAddress":{"line1":"Street","postCode":"2014","country":"NZ","latitude":-36.8},"shippingOption":{"title":"Economy Tracked","total":7.5},"paymentInstructions":"sensitive text","items":[{"listing":{"id":900001,"condition":"raw-lp","listedPrice":3.50},"quantity":1,"price":3.33}]}]}
                    """);
              }
              return response(200, "{\"number\":1,\"size\":100,\"last\":true,\"content\":[]}");
            });
    var pacerCalls = new AtomicInteger();
    var tokenExchange =
        new HttpFetchTcgTokenExchange(URI.create(FIREBASE_URI), httpClient, objectMapper);
    var credentialStore = new FetchTcgCredentialStore(secrets, objectMapper);
    var tokenProvider = new FetchTcgTokenProvider(tokenExchange, credentialStore, clock);
    var fetchTcgClient =
        new HttpFetchTcgClient(
            URI.create(FETCH_TCG_URI), httpClient, objectMapper, pacerCalls::incrementAndGet);
    var service = new FindOffersService(tokenProvider, fetchTcgClient);

    // act
    var firstPage = service.findOffers(CTX, new FindOffersRequest(null, null));
    var secondPage =
        service.findOffers(CTX, new FindOffersRequest(null, firstPage.nextContinuation()));

    // assert
    assertThat(firstPage.nextContinuation()).isEqualTo("1");
    assertThat(firstPage.offers()).hasSize(1);
    var offer = firstPage.offers().get(0);
    assertThat(offer.id()).isEqualTo(5_000_000_001L);
    assertThat(offer.status()).isEqualTo("ACCEPTED");
    assertThat(offer.acceptedAt()).isEqualTo("2026-08-11T04:42:12.476+0000");
    assertThat(offer.totalOfferPrice()).isEqualByComparingTo("3.33");
    assertThat(offer.buyerAddress().line1()).isEqualTo("Street");
    assertThat(offer.buyerAddress().postCode()).isEqualTo("2014");
    assertThat(offer.shippingOption().title()).isEqualTo("Economy Tracked");
    assertThat(offer.items().get(0).listing().listedPrice()).isEqualByComparingTo("3.50");
    assertThat(secondPage.offers()).isEmpty();
    assertThat(secondPage.nextContinuation()).isNull();
    assertThat(pacerCalls.get()).isEqualTo(2);
    var offerJson = objectMapper.readTree(objectMapper.writeValueAsString(offer));
    assertThat(offerJson.path("current_action").asText()).isEqualTo("AWAITING_PAYMENT");
    assertThat(offerJson.path("buyer_region_address").has("post_code")).isTrue();
    assertThat(offerJson.path("buyer_region_address").has("latitude")).isFalse();
    assertThat(offerJson.path("shipping_option").has("total")).isFalse();
    assertThat(objectMapper.writeValueAsString(offer))
        .doesNotContain("latitude", "payment_instructions", "sensitive text");
    assertThat(objectMapper.readTree(secrets.get("tcg_inventory")).path("alice").asText())
        .isEqualTo("refresh-two");
    assertThat(requests).hasSize(3);
    assertThat(requests.get(1).uri().toString())
        .isEqualTo(
            "https://api.fetchtcg.com/v2/private/market/offers/seller?sort=NEWEST&size=100&page=0");
    assertThat(requests.get(2).uri().getRawQuery()).endsWith("size=100&page=1");
    assertThat(requests.get(1).headers().firstValue("Authorization")).contains("Bearer bearer-one");
    assertThat(requests.get(1).method()).isEqualTo("GET");
    assertThat(requests.get(1).headers().firstValue("Accept")).contains("application/json");
    assertThat(requests.get(1).headers().firstValue("User-Agent"))
        .hasValueSatisfying(value -> assertThat(value).contains("Mozilla"));
    assertThat(requests.get(2).headers().firstValue("Authorization")).contains("Bearer bearer-one");
    assertThat(requests.stream().map(HttpRequest::uri).map(URI::getPath))
        .doesNotContain("/v1/private/profile");
  }

  @Test
  void findOffersShouldRejectInvalidPaginationBeforeReadingCredentials() {
    // arrange
    var tokenProvider = mock(FetchTcgTokenProvider.class);
    var fetchTcgClient = mock(HttpFetchTcgClient.class);
    var service = new FindOffersService(tokenProvider, fetchTcgClient);

    // act & assert
    assertThatThrownBy(() -> service.findOffers(CTX, new FindOffersRequest(101, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("limit must be between 1 and 100");
    for (var continuation : new String[] {"0", "-1", "page", "2147483648"}) {
      assertThatThrownBy(() -> service.findOffers(CTX, new FindOffersRequest(100, continuation)))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("continuation is invalid");
    }
    verifyNoInteractions(tokenProvider, fetchTcgClient);
  }

  @Test
  void findOffersShouldTranslateProviderAuthenticationAndAvailabilityErrors() throws Exception {
    // arrange
    var tokenProvider = mock(FetchTcgTokenProvider.class);
    when(tokenProvider.getBearer("alice")).thenReturn("bearer");
    var fetchTcgClient = mock(HttpFetchTcgClient.class);
    var service = new FindOffersService(tokenProvider, fetchTcgClient);
    when(fetchTcgClient.findOffers("bearer", 100, 0))
        .thenThrow(new FetchTcgAuthenticationException(401));

    // act & assert
    assertThatThrownBy(() -> service.findOffers(CTX, new FindOffersRequest(null, null)))
        .isInstanceOf(ConflictException.class)
        .hasMessage(
            "Save or re-save your FetchTCG refresh token in settings to verify your seller"
                + " account.");

    var missingClient = mock(HttpFetchTcgClient.class);
    when(missingClient.findOffers("bearer", 100, 0))
        .thenThrow(new NotFoundException("FetchTCG card was not found"));
    var unavailableService = new FindOffersService(tokenProvider, missingClient);
    assertThatThrownBy(() -> unavailableService.findOffers(CTX, new FindOffersRequest(null, null)))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("FetchTCG seller offers are temporarily unavailable");
  }

  @Test
  void findOffersShouldRetryServerErrorsAndFailOnMalformedPagination()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var httpClient = mock(HttpClient.class);
    var requests = new ArrayList<HttpRequest>();
    var unavailableResponse = response(503, "sensitive provider body");
    var successResponse = response(200, "{\"number\":1,\"size\":20,\"last\":true,\"content\":[]}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenAnswer(
            invocation -> {
              requests.add(invocation.getArgument(0));
              return requests.size() == 1 ? unavailableResponse : successResponse;
            });
    var pacerCalls = new AtomicInteger();
    var adapter =
        new HttpFetchTcgClient(
            URI.create(FETCH_TCG_URI), httpClient, new ObjectMapper(), pacerCalls::incrementAndGet);
    var tokenProvider = mock(FetchTcgTokenProvider.class);
    when(tokenProvider.getBearer("alice")).thenReturn("bearer");
    var service = new FindOffersService(tokenProvider, adapter);

    // act & assert
    assertThatThrownBy(() -> service.findOffers(CTX, new FindOffersRequest(20, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FetchTCG seller offers response contained invalid pagination")
        .hasMessageNotContaining("sensitive provider body");
    assertThat(pacerCalls.get()).isEqualTo(2);
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).uri().getRawQuery()).isEqualTo("sort=NEWEST&size=20&page=0");
    verify(httpClient, times(2))
        .send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void findOffersShouldHideMalformedProviderBodyDiagnostics()
      throws IOException, InterruptedException, ServiceException {
    var httpClient = mock(HttpClient.class);
    var providerResponse =
        response(200, "{\"number\":0,\"size\":100,\"last\":true,\"content\":[null]}");
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(providerResponse);
    var adapter =
        new HttpFetchTcgClient(URI.create(FETCH_TCG_URI), httpClient, new ObjectMapper(), () -> {});
    var tokenProvider = mock(FetchTcgTokenProvider.class);
    when(tokenProvider.getBearer("alice")).thenReturn("bearer");
    var service = new FindOffersService(tokenProvider, adapter);

    assertThatThrownBy(() -> service.findOffers(CTX, new FindOffersRequest(null, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FetchTCG seller offers response contained an invalid offer")
        .hasNoCause();
  }

  private static HttpResponse<String> response(int statusCode, String body) {
    @SuppressWarnings("unchecked")
    var response = (HttpResponse<String>) mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(statusCode);
    when(response.body()).thenReturn(body);
    return response;
  }
}
