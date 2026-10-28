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
import com.jordansimsmith.tcginventory.fetchtcg.client.CreateListingImageRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingRequest;
import com.jordansimsmith.time.FakeClock;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class CreateListingImageServiceTest {
  private static final RequestContext CTX = new RequestContext("alice");
  private static final String RECONNECT_MESSAGE =
      "Save or re-save your FetchTCG refresh token in settings to verify your seller account.";

  @Test
  void createListingImageShouldRejectInvalidInputsBeforeReadingCredentials() {
    // arrange
    var tokenProvider = mock(FetchTcgTokenProvider.class);
    var fetchTcgClient = mock(HttpFetchTcgClient.class);
    var service = new CreateListingImageService(tokenProvider, fetchTcgClient);
    var oversizedBytes = new byte[4 * 1024 * 1024 + 1];

    // act / assert
    assertThatThrownBy(() -> service.createListingImage(CTX, null))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("image_bytes is required");
    assertThatThrownBy(
            () ->
                service.createListingImage(
                    CTX, new CreateListingImageRequest(new byte[0], "x.jpg")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("image_bytes is required");
    assertThatThrownBy(
            () ->
                service.createListingImage(
                    CTX, new CreateListingImageRequest(oversizedBytes, "x.jpg")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("image_bytes must not exceed 4 MiB");
    assertThatThrownBy(
            () ->
                service.createListingImage(CTX, new CreateListingImageRequest(new byte[] {1}, " ")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("filename is required");
    assertThatThrownBy(
            () ->
                service.createListingImage(
                    CTX, new CreateListingImageRequest(new byte[] {1}, "photo\r\n.jpg")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("filename is invalid");
    assertThatThrownBy(
            () ->
                service.createListingImage(
                    CTX, new CreateListingImageRequest(new byte[] {1}, "photo\\.jpg")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("filename is invalid");
    assertThatThrownBy(
            () ->
                service.createListingImage(
                    CTX, new CreateListingImageRequest(new byte[] {1}, "photo\".jpg")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("filename is invalid");
    verifyNoInteractions(tokenProvider, fetchTcgClient);
  }

  @Test
  void createListingImageShouldSerializeImageBytesAsBase64() throws Exception {
    // arrange
    var objectMapper = new ObjectMapper();
    var request = new CreateListingImageRequest(new byte[] {0, 1, (byte) 0xff}, "card.jpg");

    // act
    var json = objectMapper.writeValueAsString(request);
    var roundTrip = objectMapper.readValue(json, CreateListingImageRequest.class);

    // assert
    assertThat(objectMapper.readTree(json).get("image_bytes").asText()).isEqualTo("AAH/");
    assertThat(roundTrip.imageBytes()).containsExactly(request.imageBytes());
    assertThat(roundTrip.filename()).isEqualTo("card.jpg");
  }

  @Test
  void createListingImageShouldUseContextUserAndReturnProviderUrl() throws Exception {
    // arrange
    var tokenProvider = mock(FetchTcgTokenProvider.class);
    var fetchTcgClient = mock(HttpFetchTcgClient.class);
    var request = new CreateListingImageRequest(new byte[] {1, 2}, "card-front.jpg");
    when(tokenProvider.getBearer("alice")).thenReturn("bearer");
    when(fetchTcgClient.createListingImage("bearer", request.imageBytes(), request.filename()))
        .thenReturn(" https://listing-img.example/image.jpg ");
    var service = new CreateListingImageService(tokenProvider, fetchTcgClient);

    // act
    var response = service.createListingImage(CTX, request);

    // assert
    assertThat(response.imageUrl()).isEqualTo(" https://listing-img.example/image.jpg ");
    verify(tokenProvider).getBearer("alice");
    verify(fetchTcgClient).createListingImage("bearer", request.imageBytes(), "card-front.jpg");
  }

  @Test
  void createListingImageShouldTranslateProviderAuthenticationFailure() throws Exception {
    // arrange
    var tokenProvider = mock(FetchTcgTokenProvider.class);
    var fetchTcgClient = mock(HttpFetchTcgClient.class);
    when(tokenProvider.getBearer("alice")).thenReturn("bearer");
    when(fetchTcgClient.createListingImage(eq("bearer"), any(byte[].class), eq("card.jpg")))
        .thenThrow(new FetchTcgAuthenticationException(401));
    var service = new CreateListingImageService(tokenProvider, fetchTcgClient);

    // act / assert
    assertThatThrownBy(
            () ->
                service.createListingImage(
                    CTX, new CreateListingImageRequest(new byte[] {1}, "card.jpg")))
        .isInstanceOf(ConflictException.class)
        .hasMessage(RECONNECT_MESSAGE);
  }

  @Test
  void createListingImageShouldTranslateProviderNotFoundToUnavailable() throws Exception {
    // arrange
    var tokenProvider = mock(FetchTcgTokenProvider.class);
    var fetchTcgClient = mock(HttpFetchTcgClient.class);
    when(tokenProvider.getBearer("alice")).thenReturn("bearer");
    when(fetchTcgClient.createListingImage(eq("bearer"), any(byte[].class), eq("card.jpg")))
        .thenThrow(new NotFoundException("provider detail"));
    var service = new CreateListingImageService(tokenProvider, fetchTcgClient);

    // act / assert
    assertThatThrownBy(
            () ->
                service.createListingImage(
                    CTX, new CreateListingImageRequest(new byte[] {1}, "card.jpg")))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("FetchTCG image upload is temporarily unavailable")
        .hasNoCause();
  }

  @Test
  void createListingImageShouldReuseTheProviderBearerAcrossAuthenticatedOperations()
      throws IOException, InterruptedException, ServiceException {
    // arrange
    var objectMapper = new ObjectMapper();
    var secrets = new FakeSecrets();
    secrets.set("tcg_inventory", "{\"alice\":\"refresh-one\"}");
    var clock = new FakeClock();
    clock.setTime(Instant.ofEpochSecond(1_700_000_000));
    var httpClient = mock(HttpClient.class);
    when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
        .thenReturn(
            response(
                200,
                "{\"id_token\":\"bearer-one\",\"refresh_token\":\"refresh-two\",\"expires_in\":\"3600\"}"),
            response(200, "{\"imageUrl\":\"https://listing-img.example/image.jpg\"}"),
            response(200, "{\"listingId\":975737,\"remainingQuantity\":1}"));
    var pacerCalls = new AtomicInteger();
    var tokenProvider =
        new FetchTcgTokenProvider(
            new HttpFetchTcgTokenExchange(
                URI.create("https://firebase.example/token"), httpClient, objectMapper),
            new FetchTcgCredentialStore(secrets, objectMapper),
            clock);
    var fetchTcgClient =
        new HttpFetchTcgClient(
            URI.create("https://api.fetchtcg.example"),
            httpClient,
            objectMapper,
            pacerCalls::incrementAndGet);
    var imageService = new CreateListingImageService(tokenProvider, fetchTcgClient);
    var listingService = new UpdateListingService(tokenProvider, fetchTcgClient);

    // act
    imageService.createListingImage(
        CTX, new CreateListingImageRequest(new byte[] {1, 2}, "card.jpg"));
    listingService.updateListing(
        CTX, new UpdateListingRequest("card", "raw-nm", 1, BigDecimal.ONE, null, List.of()));

    // assert
    assertThat(objectMapper.readTree(secrets.get("tcg_inventory")).path("alice").asText())
        .isEqualTo("refresh-two");
    assertThat(pacerCalls.get()).isEqualTo(2);
    var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(3))
        .send(requestCaptor.capture(), eq(HttpResponse.BodyHandlers.ofString()));
    var requests = requestCaptor.getAllValues();
    assertThat(requests.get(0).uri()).isEqualTo(URI.create("https://firebase.example/token"));
    assertThat(requests.get(1).uri().getPath())
        .isEqualTo("/v2/private/manage-listings/uploadListingImage");
    assertThat(requests.get(2).uri().getPath()).isEqualTo("/v2/private/manage-listings");
    assertThat(requests.get(0).headers().firstValue("Authorization")).isEmpty();
    assertThat(requests.get(1).headers().firstValue("Authorization")).hasValue("Bearer bearer-one");
    assertThat(requests.get(2).headers().firstValue("Authorization")).hasValue("Bearer bearer-one");
    assertThat(requests.stream().map(request -> request.uri().getPath()))
        .doesNotContain("/v1/private/profile");
  }

  private static HttpResponse<String> response(int statusCode, String body) {
    return new HttpResponse<>() {
      @Override
      public int statusCode() {
        return statusCode;
      }

      @Override
      public HttpRequest request() {
        return HttpRequest.newBuilder().uri(URI.create("https://api.fetchtcg.example")).build();
      }

      @Override
      public Optional<HttpResponse<String>> previousResponse() {
        return Optional.empty();
      }

      @Override
      public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (name, value) -> true);
      }

      @Override
      public String body() {
        return body;
      }

      @Override
      public Optional<SSLSession> sslSession() {
        return Optional.empty();
      }

      @Override
      public URI uri() {
        return URI.create("https://api.fetchtcg.example");
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
      }
    };
  }
}
