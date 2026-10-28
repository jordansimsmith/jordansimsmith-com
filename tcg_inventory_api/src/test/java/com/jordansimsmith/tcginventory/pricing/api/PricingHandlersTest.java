package com.jordansimsmith.tcginventory.pricing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.pricing.client.GetPricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPricingResponse;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import com.jordansimsmith.tcginventory.pricing.client.UpdatePricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.UpdatePricingResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PricingHandlersTest {
  private static final String ALLOWED_ORIGIN = "https://example.com";

  private ObjectMapper objectMapper;
  private PricingService pricingService;
  private GetPricingHandler getPricingHandler;
  private UpdatePricingHandler updatePricingHandler;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    pricingService = mock(PricingService.class);
    var factory = mock(TcgInventoryFactory.class);
    when(factory.objectMapper()).thenReturn(objectMapper);
    when(factory.requestContextFactory()).thenReturn(new RequestContextFactory());
    when(factory.httpResponseFactory())
        .thenReturn(
            new HttpResponseFactory.Builder(objectMapper)
                .withAllowedOrigin(ALLOWED_ORIGIN)
                .build());
    when(factory.pricingService()).thenReturn(pricingService);
    getPricingHandler = new GetPricingHandler(factory);
    updatePricingHandler = new UpdatePricingHandler(factory);
  }

  @Test
  void getPricingShouldDelegateWithAuthenticatedUserAndReturnClientResponse() throws Exception {
    // arrange
    var requestContext = new RequestContext("alice");
    when(pricingService.getPricing(requestContext, new GetPricingRequest()))
        .thenReturn(new GetPricingResponse("1.20"));

    // act
    var response = getPricingHandler.handleRequest(buildEvent("alice", null), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8")
        .containsEntry("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
    assertThat(objectMapper.readTree(response.getBody()).get("price_floor").asText())
        .isEqualTo("1.20");
    verify(pricingService).getPricing(requestContext, new GetPricingRequest());
  }

  @Test
  void updatePricingShouldPassTheParsedRequestAndReturnClientResponse() throws Exception {
    // arrange
    var requestContext = new RequestContext("alice");
    var request = new UpdatePricingRequest(new BigDecimal("1.2"));
    when(pricingService.updatePricing(requestContext, request))
        .thenReturn(new UpdatePricingResponse("1.20"));

    // act
    var response =
        updatePricingHandler.handleRequest(buildEvent("alice", "{\"price_floor\":1.2}"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(response.getBody()).get("price_floor").asText())
        .isEqualTo("1.20");
    verify(pricingService).updatePricing(requestContext, request);
  }

  @Test
  void updatePricingShouldRejectMalformedAndUnknownFieldsWithoutDiagnostics() throws Exception {
    // arrange
    var malformed = buildEvent("alice", "{\"price_floor\":\"secret\"");
    var unknown = buildEvent("alice", "{\"other\":\"secret\"}");

    // act
    var malformedResponse = updatePricingHandler.handleRequest(malformed, null);
    var unknownResponse = updatePricingHandler.handleRequest(unknown, null);

    // assert
    assertInvalidBodyResponse(malformedResponse.getStatusCode(), malformedResponse.getBody());
    assertInvalidBodyResponse(unknownResponse.getStatusCode(), unknownResponse.getBody());
    assertThat(malformedResponse.getBody()).doesNotContain("secret", "JsonParseException");
    assertThat(unknownResponse.getBody()).doesNotContain("secret", "UnrecognizedProperty");
  }

  @Test
  void updatePricingShouldMapCheckedServiceFailures() throws Exception {
    // arrange
    when(pricingService.updatePricing(any(RequestContext.class), any(UpdatePricingRequest.class)))
        .thenThrow(new BadRequestException("price_floor must be nonnegative"));

    // act
    var response =
        updatePricingHandler.handleRequest(
            buildEvent("alice", "{\"price_floor\":\"-1.00\"}"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("price_floor must be nonnegative");
  }

  @Test
  void updatePricingShouldPropagateUnexpectedFailures() throws Exception {
    // arrange
    when(pricingService.updatePricing(any(RequestContext.class), any(UpdatePricingRequest.class)))
        .thenThrow(new IllegalStateException("unexpected failure"));

    // act & assert
    assertThatThrownBy(
            () ->
                updatePricingHandler.handleRequest(
                    buildEvent("alice", "{\"price_floor\":\"1.00\"}"), null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  private void assertInvalidBodyResponse(int statusCode, String body) throws Exception {
    assertThat(statusCode).isEqualTo(400);
    assertThat(objectMapper.readTree(body).get("message").asText())
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
