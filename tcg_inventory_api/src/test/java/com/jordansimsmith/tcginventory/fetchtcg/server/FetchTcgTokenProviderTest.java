package com.jordansimsmith.tcginventory.fetchtcg.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.FakeSecrets;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.time.FakeClock;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

public class FetchTcgTokenProviderTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final FakeClock clock = new FakeClock();

  @Test
  void getBearerShouldExchangeOnceAndReuseBearerUntilSafetyExpiry()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1_700_000_000));
    var secrets = createSecrets("alice", "refresh-one");
    var httpClient = mock(HttpClient.class);
    var response = createResponse(200, "{\"id_token\":\"bearer-one\",\"expires_in\":\"3600\"}");
    when(httpClient.send(any(), eq(HttpResponse.BodyHandlers.ofString()))).thenReturn(response);
    var provider = createProvider(httpClient, secrets);

    // act
    var firstBearer = provider.getBearer("alice");
    var secondBearer = provider.getBearer("alice");

    // assert
    assertThat(firstBearer).isEqualTo("bearer-one");
    assertThat(secondBearer).isEqualTo("bearer-one");
    verify(httpClient, times(1)).send(any(), eq(HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void getBearerShouldRefreshAfterExpiryAndAfterCredentialReplacement()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1_700_000_000));
    var secrets = createSecrets("alice", "refresh-one");
    var httpClient = mock(HttpClient.class);
    var firstResponse =
        createResponse(200, "{\"id_token\":\"bearer-one\",\"expires_in\":\"3600\"}");
    var secondResponse =
        createResponse(200, "{\"id_token\":\"bearer-two\",\"expires_in\":\"3600\"}");
    var thirdResponse =
        createResponse(200, "{\"id_token\":\"bearer-three\",\"expires_in\":\"3600\"}");
    when(httpClient.send(any(), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(firstResponse, secondResponse, thirdResponse);
    var provider = createProvider(httpClient, secrets);

    // act
    var firstBearer = provider.getBearer("alice");
    clock.setTime(Instant.ofEpochSecond(1_700_003_541));
    var expiredBearer = provider.getBearer("alice");
    secrets.set("tcg_inventory", "{\"alice\":\"refresh-two\"}");
    var replacedBearer = provider.getBearer("alice");

    // assert
    assertThat(firstBearer).isEqualTo("bearer-one");
    assertThat(expiredBearer).isEqualTo("bearer-two");
    assertThat(replacedBearer).isEqualTo("bearer-three");
    verify(httpClient, times(3)).send(any(), eq(HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void getBearerShouldPersistRotatedTokenBeforeReuse()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var secrets = new FakeSecrets();
    secrets.set("tcg_inventory", "{\"alice\":\"refresh-one\",\"bob\":\"bob-token\"}");
    var httpClient = mock(HttpClient.class);
    var response =
        createResponse(
            200,
            "{\"id_token\":\"bearer-one\",\"refresh_token\":\"refresh-two\",\"expires_in\":\"3600\"}");
    when(httpClient.send(any(), eq(HttpResponse.BodyHandlers.ofString()))).thenReturn(response);
    var provider = createProvider(httpClient, secrets);

    // act
    var bearer = provider.getBearer("alice");
    var repeatedBearer = provider.getBearer("alice");

    // assert
    assertThat(bearer).isEqualTo("bearer-one");
    assertThat(repeatedBearer).isEqualTo("bearer-one");
    assertThat(objectMapper.readTree(secrets.get("tcg_inventory")).path("bob").asText())
        .isEqualTo("bob-token");
    assertThat(objectMapper.readTree(secrets.get("tcg_inventory")).path("alice").asText())
        .isEqualTo("refresh-two");
    verify(httpClient, times(1)).send(any(), eq(HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void getBearerShouldNotCacheFailedExchanges()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var secrets = createSecrets("alice", "refresh-one");
    var httpClient = mock(HttpClient.class);
    var failedResponse = createResponse(503, "provider-response-secret");
    var successfulResponse =
        createResponse(200, "{\"id_token\":\"later-bearer\",\"expires_in\":\"3600\"}");
    when(httpClient.send(any(), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(failedResponse, successfulResponse);
    var provider = createProvider(httpClient, secrets);

    // act
    assertThatThrownBy(() -> provider.getBearer("alice"))
        .hasMessage("Firebase token exchange is temporarily unavailable")
        .hasMessageNotContaining("provider-response-secret");
    var bearer = provider.getBearer("alice");

    // assert
    assertThat(bearer).isEqualTo("later-bearer");
    verify(httpClient, times(2)).send(any(), eq(HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void getBearerShouldPropagateTokenExchangeServiceErrors()
      throws IOException, InterruptedException {
    // arrange
    var secrets = createSecrets("alice", "refresh-one");
    var httpClient = mock(HttpClient.class);
    var response = createResponse(400, "{\"error\":{\"message\":\"TOKEN_EXPIRED\"}}");
    when(httpClient.send(any(), eq(HttpResponse.BodyHandlers.ofString()))).thenReturn(response);
    var provider = createProvider(httpClient, secrets);

    // act & assert
    assertThatThrownBy(() -> provider.getBearer("alice"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("The refresh token was rejected. Check it and try again.");
  }

  @Test
  void getBearerShouldKeepCacheLocalToUserAndProviderInstance()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var secrets = new FakeSecrets();
    secrets.set("tcg_inventory", "{\"alice\":\"alice-token\",\"bob\":\"bob-token\"}");
    var httpClient = mock(HttpClient.class);
    var aliceResponse =
        createResponse(200, "{\"id_token\":\"alice-bearer\",\"expires_in\":\"3600\"}");
    var bobResponse = createResponse(200, "{\"id_token\":\"bob-bearer\",\"expires_in\":\"3600\"}");
    var otherInstanceResponse =
        createResponse(200, "{\"id_token\":\"other-instance-bearer\",\"expires_in\":\"3600\"}");
    when(httpClient.send(any(), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(aliceResponse, bobResponse, otherInstanceResponse);
    var firstProvider = createProvider(httpClient, secrets);
    var secondProvider = createProvider(httpClient, secrets);

    // act
    var aliceBearer = firstProvider.getBearer("alice");
    var bobBearer = firstProvider.getBearer("bob");
    var secondInstanceBearer = secondProvider.getBearer("alice");

    // assert
    assertThat(aliceBearer).isEqualTo("alice-bearer");
    assertThat(bobBearer).isEqualTo("bob-bearer");
    assertThat(secondInstanceBearer).isEqualTo("other-instance-bearer");
    verify(httpClient, times(3)).send(any(), eq(HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void getBearerShouldAvoidCachingTokensInsideSafetyMargin()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var secrets = createSecrets("alice", "refresh-one");
    var httpClient = mock(HttpClient.class);
    var firstResponse =
        createResponse(200, "{\"id_token\":\"short-bearer-one\",\"expires_in\":\"30\"}");
    var secondResponse =
        createResponse(200, "{\"id_token\":\"short-bearer-two\",\"expires_in\":\"30\"}");
    when(httpClient.send(any(), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(firstResponse, secondResponse);
    var provider = createProvider(httpClient, secrets);

    // act
    var firstBearer = provider.getBearer("alice");
    var secondBearer = provider.getBearer("alice");

    // assert
    assertThat(firstBearer).isEqualTo("short-bearer-one");
    assertThat(secondBearer).isEqualTo("short-bearer-two");
    verify(httpClient, times(2)).send(any(), eq(HttpResponse.BodyHandlers.ofString()));
  }

  @Test
  void getBearerShouldSerializeConcurrentRefreshesWithinOneInstance()
      throws InterruptedException, ExecutionException, IOException, ServiceException {
    // arrange
    var secrets = createSecrets("alice", "refresh-one");
    var httpClient = mock(HttpClient.class);
    var response = createResponse(200, "{\"id_token\":\"shared-bearer\",\"expires_in\":\"3600\"}");
    when(httpClient.send(any(), eq(HttpResponse.BodyHandlers.ofString()))).thenReturn(response);
    var provider = createProvider(httpClient, secrets);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> provider.getBearer("alice"));
      var second = executor.submit(() -> provider.getBearer("alice"));

      // act
      var bearers = List.of(first.get(), second.get());

      // assert
      assertThat(bearers).containsExactly("shared-bearer", "shared-bearer");
      verify(httpClient, times(1)).send(any(), eq(HttpResponse.BodyHandlers.ofString()));
    }
  }

  @Test
  void getBearerShouldRejectMissingCredentialsWithoutExposingStorage()
      throws IOException, InterruptedException {
    // arrange
    var secrets = createSecrets("bob", "other-user-token");
    var httpClient = mock(HttpClient.class);
    var provider = createProvider(httpClient, secrets);

    // act & assert
    assertThatThrownBy(() -> provider.getBearer("alice"))
        .isInstanceOf(ConflictException.class)
        .hasMessage(
            "Save or re-save your FetchTCG refresh token in settings to verify your seller"
                + " account.")
        .hasMessageNotContaining("other-user-token");
    verify(httpClient, times(0)).send(any(), eq(HttpResponse.BodyHandlers.ofString()));
  }

  private FakeSecrets createSecrets(String user, String refreshToken) {
    var secrets = new FakeSecrets();
    secrets.set("tcg_inventory", "{\"" + user + "\":\"" + refreshToken + "\"}");
    return secrets;
  }

  private FetchTcgTokenProvider createProvider(HttpClient httpClient, FakeSecrets secrets) {
    var exchange =
        new HttpFetchTcgTokenExchange(
            URI.create("https://firebase.example/token"), httpClient, objectMapper);
    var store = new FetchTcgCredentialStore(secrets, objectMapper);
    return new FetchTcgTokenProvider(exchange, store, clock);
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> createResponse(int statusCode, String body) {
    var response = (HttpResponse<String>) mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(statusCode);
    when(response.body()).thenReturn(body);
    return response;
  }
}
