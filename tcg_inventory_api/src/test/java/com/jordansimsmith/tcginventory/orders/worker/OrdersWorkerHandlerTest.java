package com.jordansimsmith.tcginventory.orders.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.orders.server.FulfillmentMessage;
import com.jordansimsmith.tcginventory.orders.server.FulfillmentMessageHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrdersWorkerHandlerTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final FulfillmentMessageHandler messageHandler = mock(FulfillmentMessageHandler.class);
  private OrdersWorkerHandler handler;

  @BeforeEach
  void setUp() {
    var factory = mock(TcgInventoryFactory.class);
    when(factory.objectMapper()).thenReturn(objectMapper);
    when(factory.fulfillmentMessageHandler()).thenReturn(messageHandler);
    handler = new OrdersWorkerHandler(factory);
  }

  @Test
  void handleRequestShouldDeserializeMessageAndDelegateWithItsUser() throws Exception {
    var event = event("{\"user\":\"seller\",\"order_id\":\"order-123\"}");

    var result = handler.handleRequest(event, null);

    assertThat(result).isNull();
    verify(messageHandler)
        .handleMessage(new RequestContext("seller"), new FulfillmentMessage("seller", "order-123"));
  }

  @Test
  void handleRequestShouldFailMalformedJsonBeforeDelegating() {
    var event = event("not-json");

    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(JsonProcessingException.class);
    verifyNoInteractions(messageHandler);
  }

  @Test
  void handleRequestShouldFailUnexpectedRecordCountBeforeDelegating() {
    var event = new SQSEvent();
    event.setRecords(List.of());

    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(messageHandler);
  }

  @Test
  void handleRequestShouldFailMultipleRecordsBeforeDelegating() {
    var event = new SQSEvent();
    var firstMessage = new SQSEvent.SQSMessage();
    firstMessage.setBody("{}");
    var secondMessage = new SQSEvent.SQSMessage();
    secondMessage.setBody("{}");
    event.setRecords(List.of(firstMessage, secondMessage));

    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(messageHandler);
  }

  @Test
  void handleRequestShouldRetainCheckedServiceFailureAsCause() throws Exception {
    var event = event("{\"user\":\"seller\",\"order_id\":\"order-123\"}");
    var failure = new NotFoundException("missing order");
    doThrow(failure).when(messageHandler).handleMessage(any(), any());

    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  @Test
  void handleRequestShouldRetainRuntimeFailureAsCause() throws Exception {
    var event = event("{\"user\":\"seller\",\"order_id\":\"order-123\"}");
    var failure = new IllegalStateException("storage failed");
    doThrow(failure).when(messageHandler).handleMessage(any(), any());

    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  private SQSEvent event(String body) {
    var message = new SQSEvent.SQSMessage();
    message.setBody(body);
    var event = new SQSEvent();
    event.setRecords(List.of(message));
    return event;
  }
}
