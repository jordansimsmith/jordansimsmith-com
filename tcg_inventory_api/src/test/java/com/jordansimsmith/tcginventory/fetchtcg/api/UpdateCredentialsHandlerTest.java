package com.jordansimsmith.tcginventory.fetchtcg.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateCredentialsRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.UpdateCredentialsResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class UpdateCredentialsHandlerTest {
  private static final String ALLOWED_ORIGIN = "https://example.com";

  private ObjectMapper objectMapper;
  private FetchTcgService fetchTcgService;
  private UpdateCredentialsHandler handler;

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
    handler = new UpdateCredentialsHandler(factory);
  }

  @Test
  void handleRequestShouldPreserveTokenAndReturnAccountMetadata() throws Exception {
    // arrange
    var requestContext = new RequestContext("seller@example.com");
    var token = "  refresh/token?value=a&b  ";
    when(fetchTcgService.updateCredentials(requestContext, new UpdateCredentialsRequest(token)))
        .thenReturn(new UpdateCredentialsResponse("verified-seller", 1700000000L));
    var event = buildEvent("seller@example.com", "{\"refresh_token\":\"" + token + "\"}");

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8");
    assertThat(response.getHeaders()).containsEntry("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
    assertThat(objectMapper.readTree(response.getBody()))
        .isEqualTo(
            objectMapper.readTree(
                "{\"seller_username\":\"verified-seller\",\"credentials_updated_at\":1700000000}"));
    verify(fetchTcgService).updateCredentials(requestContext, new UpdateCredentialsRequest(token));
  }

  @Test
  void handleRequestShouldReturnBadRequestForMissingBlankAndMalformedBodies() throws Exception {
    // arrange
    var missingBody = buildEvent("seller", null);
    var blankBody = buildEvent("seller", "  ");
    var malformedBody = buildEvent("seller", "{\"refresh_token\":\"secret-token\"");

    // act
    var missingResponse = handler.handleRequest(missingBody, null);
    var blankResponse = handler.handleRequest(blankBody, null);
    var malformedResponse = handler.handleRequest(malformedBody, null);

    // assert
    assertInvalidBodyResponse(missingResponse);
    assertInvalidBodyResponse(blankResponse);
    assertInvalidBodyResponse(malformedResponse);
    assertThat(malformedResponse.getBody()).doesNotContain("secret-token", "JsonParseException");
  }

  @Test
  void handleRequestShouldReturnBadRequestForIncompatibleJsonShapes() throws Exception {
    // arrange
    var arrayResponse = handler.handleRequest(buildEvent("seller", "[]"), null);
    var nullResponse = handler.handleRequest(buildEvent("seller", "null"), null);

    // assert
    assertInvalidBodyResponse(arrayResponse);
    assertInvalidBodyResponse(nullResponse);
  }

  @Test
  void handleRequestShouldMapOperationValidationErrors() throws Exception {
    // arrange
    when(fetchTcgService.updateCredentials(
            new RequestContext("seller"), new UpdateCredentialsRequest(null)))
        .thenThrow(new BadRequestException("refresh_token is required"));

    // act
    var response = handler.handleRequest(buildEvent("seller", "{}"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("refresh_token is required");
  }

  @Test
  void handleRequestShouldMapServiceUnavailableTo503() throws Exception {
    // arrange
    when(fetchTcgService.updateCredentials(
            eq(new RequestContext("seller")), any(UpdateCredentialsRequest.class)))
        .thenThrow(new ServiceUnavailableException("FetchTCG is temporarily unavailable"));

    // act
    var response =
        handler.handleRequest(buildEvent("seller", "{\"refresh_token\":\"token\"}"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(503);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("FetchTCG is temporarily unavailable");
  }

  private void assertInvalidBodyResponse(APIGatewayV2HTTPResponse response) throws Exception {
    assertThat(response.getStatusCode()).isEqualTo(400);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("Invalid request body");
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
