package com.jordansimsmith.tcginventory.fetchtcg.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GetAccountHandlerTest {
  private static final String ALLOWED_ORIGIN = "https://example.com";

  private ObjectMapper objectMapper;
  private FetchTcgService fetchTcgService;
  private GetAccountHandler handler;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    fetchTcgService = mock(FetchTcgService.class);
    var factory = mock(TcgInventoryFactory.class);
    when(factory.objectMapper()).thenReturn(objectMapper);
    when(factory.requestContextFactory()).thenReturn(new RequestContextFactory());
    when(factory.httpResponseFactory())
        .thenReturn(
            new HttpResponseFactory.Builder(objectMapper)
                .withAllowedOrigin(ALLOWED_ORIGIN)
                .build());
    when(factory.fetchTcgService()).thenReturn(fetchTcgService);
    handler = new GetAccountHandler(factory);
  }

  @Test
  void handleRequestShouldCallServiceAndReturnAccountMetadata() throws Exception {
    // arrange
    var requestContext = new RequestContext("seller@example.com");
    when(fetchTcgService.getAccount(requestContext, new GetAccountRequest()))
        .thenReturn(new GetAccountResponse("verified-seller", 1700000000L));

    // act
    var response = handler.handleRequest(buildEvent("seller@example.com", null), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8");
    assertThat(response.getHeaders()).containsEntry("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
    assertThat(objectMapper.readTree(response.getBody()))
        .isEqualTo(
            objectMapper.readTree(
                "{\"seller_username\":\"verified-seller\",\"credentials_updated_at\":1700000000}"));
    verify(fetchTcgService).getAccount(requestContext, new GetAccountRequest());
  }

  @Test
  void handleRequestShouldMapMissingAccountToNotFound() throws Exception {
    // arrange
    when(fetchTcgService.getAccount(new RequestContext("seller"), new GetAccountRequest()))
        .thenThrow(new NotFoundException("FetchTCG account is not configured"));

    // act
    var response = handler.handleRequest(buildEvent("seller", null), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("FetchTCG account is not configured");
  }

  @Test
  void handleRequestShouldMapServiceUnavailableTo503() throws Exception {
    // arrange
    when(fetchTcgService.getAccount(new RequestContext("seller"), new GetAccountRequest()))
        .thenThrow(new ServiceUnavailableException("FetchTCG is temporarily unavailable"));

    // act
    var response = handler.handleRequest(buildEvent("seller", null), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(503);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("FetchTCG is temporarily unavailable");
  }

  @Test
  void handleRequestShouldPropagateUnexpectedFailures() throws Exception {
    // arrange
    var failure = new IllegalStateException("unexpected failure");
    when(fetchTcgService.getAccount(new RequestContext("seller"), new GetAccountRequest()))
        .thenThrow(failure);

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(buildEvent("seller", null), null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  private APIGatewayV2HTTPEvent buildEvent(String user, String body) {
    var auth =
        Base64.getEncoder().encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", "Basic " + auth))
        .withBody(body)
        .build();
  }
}
