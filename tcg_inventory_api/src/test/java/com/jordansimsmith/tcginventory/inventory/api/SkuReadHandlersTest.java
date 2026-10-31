package com.jordansimsmith.tcginventory.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusResponse;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuResponse;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkuReadHandlersTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final InventoryService inventoryService = mock(InventoryService.class);
  private FindSkusHandler findSkusHandler;
  private GetSkuHandler getSkuHandler;

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
    findSkusHandler = new FindSkusHandler(factory);
    getSkuHandler = new GetSkuHandler(factory);
  }

  @Test
  void findSkusShouldDelegateQueryAndReturnServiceResponse() throws Exception {
    // arrange
    var responseBody = new FindSkusResponse(List.of(sku()), "next");
    when(inventoryService.findSkus(any(), any())).thenReturn(responseBody);
    var event = event();
    event.setQueryStringParameters(Map.of("game", "mtg", "limit", "10"));

    // act
    var response = findSkusHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders().get("Content-Type"))
        .isEqualTo("application/json; charset=utf-8");
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
        .isEqualTo("https://tcg-inventory.jordansimsmith.com");
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("skus").get(0).get("external_id").asText()).isEqualTo("card-id");
    assertThat(body.get("skus").get(0).get("last_published_price").isNumber()).isTrue();
    verify(inventoryService)
        .findSkus(new RequestContext("jordan"), new FindSkusRequest("mtg", null, 10, null));
  }

  @Test
  void getSkuShouldReturnWrappedSkuAndMapNotFound() throws Exception {
    // arrange
    when(inventoryService.getSku(any(), any())).thenReturn(new GetSkuResponse(sku()));

    // act
    var event = event();
    event.setPathParameters(Map.of("sku_id", "sku%23opaque"));
    var response = getSkuHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("sku").get("sku_id").asText()).isEqualTo("sku#opaque");
    assertThat(body.get("sku").get("last_published_price").isNumber()).isTrue();
    verify(inventoryService).getSku(new RequestContext("jordan"), new GetSkuRequest("sku#opaque"));

    when(inventoryService.getSku(any(), any())).thenThrow(new NotFoundException("Not Found"));
    event.setPathParameters(Map.of("sku_id", "missing"));
    var missing = getSkuHandler.handleRequest(event, null);
    assertThat(missing.getStatusCode()).isEqualTo(404);
  }

  @Test
  void findSkusShouldRejectInvalidLimitBeforeCallingService() {
    // act
    var event = event();
    event.setQueryStringParameters(Map.of("game", "mtg", "limit", "many"));
    var response = findSkusHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
  }

  private static Sku sku() {
    return new Sku(
        "sku#opaque",
        "mtg",
        "card-id",
        "Card",
        "set",
        "Set",
        "1",
        "normal",
        "NM",
        new BigDecimal("0.00"));
  }

  private static APIGatewayV2HTTPEvent event() {
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", "Basic am9yZGFuOnBhc3N3b3Jk"))
        .build();
  }
}
