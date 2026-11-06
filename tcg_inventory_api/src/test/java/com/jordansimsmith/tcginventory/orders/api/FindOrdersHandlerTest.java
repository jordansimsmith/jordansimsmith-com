package com.jordansimsmith.tcginventory.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.orders.client.DeliveryMode;
import com.jordansimsmith.tcginventory.orders.client.FindOrdersRequest;
import com.jordansimsmith.tcginventory.orders.client.FindOrdersResponse;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import com.jordansimsmith.tcginventory.orders.client.OrderState;
import com.jordansimsmith.tcginventory.orders.client.OrderSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FindOrdersHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final OrderService orderService = mock(OrderService.class);
  private FindOrdersHandler handler;

  @BeforeEach
  void setUp() {
    var factory = mock(TcgInventoryFactory.class);
    when(factory.orderService()).thenReturn(orderService);
    when(factory.requestContextFactory()).thenReturn(new RequestContextFactory());
    when(factory.httpResponseFactory())
        .thenReturn(
            new HttpResponseFactory.Builder(objectMapper)
                .withAllowedOrigin("https://tcg-inventory.jordansimsmith.com")
                .build());
    handler = new FindOrdersHandler(factory);
  }

  @Test
  void handleRequestShouldDelegateAndReturnResponse() throws Exception {
    var event = event(Map.of("limit", "7", "continuation", "opaque"));
    var result =
        new FindOrdersResponse(
            List.of(
                new OrderSummary(
                    "42",
                    OrderState.AWAITING_PAYMENT,
                    1700000000,
                    DeliveryMode.DELIVERY,
                    "12.00",
                    "10.00",
                    "11.00",
                    1,
                    List.of())),
            null);
    when(orderService.findOrders(any(), any())).thenReturn(result);

    var response = handler.handleRequest(event, null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders().get("Content-Type"))
        .isEqualTo("application/json; charset=utf-8");
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
        .isEqualTo("https://tcg-inventory.jordansimsmith.com");
    assertThat(objectMapper.readTree(response.getBody()).get("orders").get(0).get("state").asText())
        .isEqualTo("awaiting_payment");
    verify(orderService)
        .findOrders(new RequestContext("jordan"), new FindOrdersRequest(7, "opaque"));
  }

  @Test
  void invalidLimitShouldReturnSafeBadRequestWithoutCallingService() throws Exception {
    var response = handler.handleRequest(event(Map.of("limit", "abc")), null);

    assertThat(response.getStatusCode()).isEqualTo(400);
    assertThat(response.getBody()).contains("Invalid limit");
    verify(orderService, never()).findOrders(any(), any());
  }

  @Test
  void serviceErrorsShouldUseSharedResponseMapping() throws Exception {
    when(orderService.findOrders(any(), any())).thenThrow(new NotFoundException("missing"));

    var response = handler.handleRequest(event(null), null);

    assertThat(response.getStatusCode()).isEqualTo(404);
    assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
        .isEqualTo("missing");
  }

  @Test
  void unexpectedFailuresShouldPropagate() throws Exception {
    var failure = new IllegalStateException("broken");
    when(orderService.findOrders(eq(new RequestContext("jordan")), any())).thenThrow(failure);

    assertThatThrownBy(() -> handler.handleRequest(event(null), null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  private static APIGatewayV2HTTPEvent event(Map<String, String> queryParameters) {
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", AUTH_HEADER))
        .withQueryStringParameters(queryParameters)
        .build();
  }
}
