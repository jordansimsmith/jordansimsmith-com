package com.jordansimsmith.tcginventory.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.orders.client.ConfirmOrderRequest;
import com.jordansimsmith.tcginventory.orders.client.ConfirmOrderResponse;
import com.jordansimsmith.tcginventory.orders.client.OrderService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfirmOrderHandlerTest {
  private static final String AUTH_HEADER = "Basic am9yZGFuOnBhc3N3b3Jk";
  private static final String ORIGIN = "https://tcg-inventory.jordansimsmith.com";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final OrderService orderService = mock(OrderService.class);
  private ConfirmOrderHandler handler;

  @BeforeEach
  void setUp() {
    var factory = mock(TcgInventoryFactory.class);
    when(factory.orderService()).thenReturn(orderService);
    when(factory.requestContextFactory()).thenReturn(new RequestContextFactory());
    when(factory.httpResponseFactory())
        .thenReturn(
            new HttpResponseFactory.Builder(objectMapper).withAllowedOrigin(ORIGIN).build());
    handler = new ConfirmOrderHandler(factory);
  }

  @Test
  void handleRequestShouldDelegateAndReturnBodylessAcceptedResponse() throws Exception {
    var orderId = " opaque:#/id ";
    when(orderService.confirmOrder(any(), any())).thenReturn(new ConfirmOrderResponse());

    var response = handler.handleRequest(event(orderId), null);

    assertThat(response.getStatusCode()).isEqualTo(202);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().get("Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
    assertThat(response.getHeaders().get("Content-Type"))
        .isEqualTo("application/json; charset=utf-8");
    verify(orderService)
        .confirmOrder(new RequestContext("jordan"), new ConfirmOrderRequest(orderId));
  }

  @Test
  void checkedErrorsShouldUseSharedResponseMapping() throws Exception {
    for (var failure :
        List.of(
            new BadRequestException("order_id is required"),
            new NotFoundException("Not Found"),
            new ConflictException("order is not ready to pick"),
            new ServiceUnavailableException("orders unavailable"))) {
      doThrow(failure).when(orderService).confirmOrder(any(), any());
      var response = handler.handleRequest(event("42"), null);
      assertThat(response.getStatusCode())
          .isEqualTo(
              failure instanceof BadRequestException
                  ? 400
                  : failure instanceof NotFoundException
                      ? 404
                      : failure instanceof ConflictException ? 409 : 503);
      assertThat(response.getHeaders().get("Content-Type"))
          .isEqualTo("application/json; charset=utf-8");
      assertThat(objectMapper.readTree(response.getBody()).get("message").asText())
          .isEqualTo(failure.getMessage());
    }
  }

  @Test
  void unexpectedFailuresShouldPropagateWithCause() throws Exception {
    var failure = new IllegalStateException("broken");
    when(orderService.confirmOrder(eq(new RequestContext("jordan")), any())).thenThrow(failure);

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
