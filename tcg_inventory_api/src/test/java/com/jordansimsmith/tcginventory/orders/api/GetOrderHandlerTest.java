package com.jordansimsmith.tcginventory.orders.api;

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
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.orders.client.GetOrderRequest;
import com.jordansimsmith.tcginventory.orders.client.GetOrderResponse;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import com.jordansimsmith.tcginventory.orders.client.OrderState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetOrderHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final OrderService orderService = mock(OrderService.class);
  private GetOrderHandler handler;

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
    handler = new GetOrderHandler(factory);
  }

  @Test
  void handleRequestShouldDelegateAndSerializeResponseDirectly() throws Exception {
    var result =
        new GetOrderResponse(
            "42",
            OrderState.TO_PICK,
            1700000000,
            null,
            null,
            null,
            null,
            null,
            "1.00",
            "1.00",
            1,
            List.of(),
            List.of());
    when(orderService.getOrder(any(), any())).thenReturn(result);

    var response = handler.handleRequest(event("42"), null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders().get("Content-Type"))
        .isEqualTo("application/json; charset=utf-8");
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin"))
        .isEqualTo("https://tcg-inventory.jordansimsmith.com");
    assertThat(objectMapper.readTree(response.getBody()).get("order_id").asText()).isEqualTo("42");
    verify(orderService).getOrder(new RequestContext("jordan"), new GetOrderRequest("42"));
  }

  @Test
  void serviceErrorsShouldUseSharedResponseMapping() throws Exception {
    when(orderService.getOrder(any(), any())).thenThrow(new NotFoundException("Not Found"));

    var response = handler.handleRequest(event("missing"), null);

    assertThat(response.getStatusCode()).isEqualTo(404);
    assertThat(response.getBody()).contains("Not Found");
  }

  @Test
  void unexpectedFailuresShouldPropagate() throws Exception {
    var failure = new IllegalStateException("broken");
    when(orderService.getOrder(any(), any())).thenThrow(failure);

    assertThatThrownBy(() -> handler.handleRequest(event("42"), null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  private static APIGatewayV2HTTPEvent event(String orderId) {
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", AUTH_HEADER))
        .withPathParameters(Map.of("order_id", orderId))
        .build();
  }
}
