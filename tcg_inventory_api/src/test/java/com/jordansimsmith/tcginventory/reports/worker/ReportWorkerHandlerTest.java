package com.jordansimsmith.tcginventory.reports.worker;

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
import com.jordansimsmith.tcginventory.reports.server.ReportMessage;
import com.jordansimsmith.tcginventory.reports.server.ReportMessageHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportWorkerHandlerTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ReportMessageHandler messageHandler = mock(ReportMessageHandler.class);
  private ReportWorkerHandler handler;

  @BeforeEach
  void setUp() {
    var factory = mock(TcgInventoryFactory.class);
    when(factory.objectMapper()).thenReturn(objectMapper);
    when(factory.reportMessageHandler()).thenReturn(messageHandler);
    handler = new ReportWorkerHandler(factory);
  }

  @Test
  void handleRequestShouldDeserializeMessageAndDelegateWithItsUser() throws Exception {
    var event = event("{\"user\":\"seller\",\"generation_id\":\"generation-123\"}");

    var result = handler.handleRequest(event, null);

    assertThat(result).isNull();
    verify(messageHandler)
        .handleMessage(new RequestContext("seller"), new ReportMessage("seller", "generation-123"));
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
  void handleRequestShouldFailWhenMessageProcessingFails() throws Exception {
    var event = event("{\"user\":\"seller\",\"generation_id\":\"generation-123\"}");
    var failure = new NotFoundException("missing generation");
    doThrow(failure).when(messageHandler).handleMessage(any(), any());

    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  @Test
  void handleRequestShouldRetainRuntimeFailureAsCause() throws Exception {
    var event = event("{\"user\":\"seller\",\"generation_id\":\"generation-123\"}");
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
