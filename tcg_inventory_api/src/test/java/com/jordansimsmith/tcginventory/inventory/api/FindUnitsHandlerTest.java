package com.jordansimsmith.tcginventory.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsResponse;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FindUnitsHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final InventoryService inventoryService = mock(InventoryService.class);
  private FindUnitsHandler handler;

  @BeforeEach
  void setUp() {
    var factory = mock(TcgInventoryFactory.class);
    when(factory.inventoryService()).thenReturn(inventoryService);
    when(factory.requestContextFactory()).thenReturn(new RequestContextFactory());
    when(factory.httpResponseFactory())
        .thenReturn(
            new HttpResponseFactory.Builder(objectMapper)
                .withAllowedOrigin("https://tcg-inventory.jordansimsmith.com")
                .build());
    handler = new FindUnitsHandler(factory);
  }

  @Test
  void handleRequestShouldDecodePathPassRequestAndSerializeServiceResponse() throws Exception {
    // arrange
    var event = event("SKU%23opaque", "100", "next-page");
    var responseBody =
        new FindUnitsResponse(List.of(new Unit(102, "A1-2", "in_stock", List.of())), null);
    when(inventoryService.findUnits(any(), any())).thenReturn(responseBody);

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders().get("Content-Type"))
        .isEqualTo("application/json; charset=utf-8");
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
        .isEqualTo("https://tcg-inventory.jordansimsmith.com");
    assertThat(objectMapper.readTree(response.getBody()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {"units":[{"sequence_number":102,"location":"A1-2","status":"in_stock","photos":[]}],"next_continuation":null}
                """));
    verify(inventoryService)
        .findUnits(
            new RequestContext("jordan"), new FindUnitsRequest("SKU#opaque", 100, "next-page"));
  }

  @Test
  void handleRequestShouldUseNullOptionalQueryValues() throws Exception {
    // arrange
    when(inventoryService.findUnits(any(), any()))
        .thenReturn(new FindUnitsResponse(List.of(), null));

    // act
    handler.handleRequest(event("sku", null, null), null);

    // assert
    verify(inventoryService)
        .findUnits(new RequestContext("jordan"), new FindUnitsRequest("sku", null, null));
  }

  @Test
  void handleRequestShouldReturnBadRequestForInvalidLimit() throws Exception {
    // arrange
    var event = event("sku", "many", null);

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("Invalid limit");
    verifyNoInteractions(inventoryService);
  }

  @Test
  void handleRequestShouldReturnBadRequestForMalformedPathEncoding() throws Exception {
    // arrange
    var event = event("sku%2", null, null);

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("Invalid sku_id");
    verifyNoInteractions(inventoryService);
  }

  @Test
  void handleRequestShouldMapServiceException() throws Exception {
    // arrange
    when(inventoryService.findUnits(any(), any()))
        .thenThrow(new NotFoundException("missing unit collection"));

    // act
    var response = handler.handleRequest(event("sku", null, null), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("missing unit collection");
  }

  @Test
  void handleRequestShouldPropagateUnexpectedFailure() throws Exception {
    // arrange
    when(inventoryService.findUnits(any(), any())).thenThrow(new IllegalStateException("broken"));

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(event("sku", null, null), null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  private APIGatewayV2HTTPEvent event(String skuId, String limit, String continuation) {
    var queryParams = new HashMap<String, String>();
    if (limit != null) {
      queryParams.put("limit", limit);
    }
    if (continuation != null) {
      queryParams.put("continuation", continuation);
    }
    var event = new APIGatewayV2HTTPEvent();
    event.setHeaders(Map.of("Authorization", AUTH_HEADER));
    event.setPathParameters(Map.of("sku_id", skuId));
    event.setQueryStringParameters(queryParams.isEmpty() ? null : queryParams);
    return event;
  }
}
