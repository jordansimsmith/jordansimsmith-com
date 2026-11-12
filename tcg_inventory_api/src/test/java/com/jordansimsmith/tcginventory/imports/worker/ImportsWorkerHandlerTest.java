package com.jordansimsmith.tcginventory.imports.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.imports.server.AppraisalMessageHandler;
import com.jordansimsmith.tcginventory.imports.server.ImportConfirmationMessageHandler;
import com.jordansimsmith.tcginventory.imports.server.ImportMessage;
import com.jordansimsmith.tcginventory.imports.server.ImportMessageType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportsWorkerHandlerTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AppraisalMessageHandler appraisalMessageHandler =
      mock(AppraisalMessageHandler.class);
  private final ImportConfirmationMessageHandler importConfirmationMessageHandler =
      mock(ImportConfirmationMessageHandler.class);
  private final ImportsWorkerHandler handler =
      new ImportsWorkerHandler(
          objectMapper, appraisalMessageHandler, importConfirmationMessageHandler);

  @Test
  void handleRequestShouldDeserializeMessageAndDelegateWithItsUser() throws Exception {
    var event = event("{\"user\":\"seller\",\"import_id\":\"import-123\",\"type\":\"appraise\"}");

    var result = handler.handleRequest(event, null);

    assertThat(result).isNull();
    verify(appraisalMessageHandler)
        .handleMessage(
            new RequestContext("seller"),
            new ImportMessage("seller", "import-123", ImportMessageType.APPRAISE));
    verifyNoInteractions(importConfirmationMessageHandler);
  }

  @Test
  void handleRequestShouldDelegateConfirmationMessages() throws Exception {
    var event = event("{\"user\":\"seller\",\"import_id\":\"import-123\",\"type\":\"confirm\"}");

    handler.handleRequest(event, null);

    verify(importConfirmationMessageHandler)
        .handleMessage(
            new RequestContext("seller"),
            new ImportMessage("seller", "import-123", ImportMessageType.CONFIRM));
    verifyNoInteractions(appraisalMessageHandler);
  }

  @Test
  void handleRequestShouldFailMalformedJsonBeforeDelegating() {
    var event = event("not-json");

    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(JsonProcessingException.class);
    verifyNoInteractions(appraisalMessageHandler, importConfirmationMessageHandler);
  }

  @Test
  void handleRequestShouldFailUnknownMessageTypeBeforeDelegating() {
    var event = event("{\"user\":\"seller\",\"import_id\":\"import-123\",\"type\":\"unknown\"}");

    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(JsonProcessingException.class);
    verifyNoInteractions(appraisalMessageHandler, importConfirmationMessageHandler);
  }

  @Test
  void handleRequestShouldFailMissingMessageTypeBeforeDelegating() {
    var event = event("{\"user\":\"seller\",\"import_id\":\"import-123\",\"type\":null}");

    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(appraisalMessageHandler, importConfirmationMessageHandler);
  }

  @Test
  void handleRequestShouldFailUnexpectedRecordCountBeforeDelegating() {
    var event = new SQSEvent();
    event.setRecords(List.of());

    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(appraisalMessageHandler, importConfirmationMessageHandler);
  }

  @Test
  void handleRequestShouldFailMultipleRecordsBeforeDelegating() {
    var firstMessage = new SQSEvent.SQSMessage();
    firstMessage.setBody("{}");
    var secondMessage = new SQSEvent.SQSMessage();
    secondMessage.setBody("{}");
    var event = new SQSEvent();
    event.setRecords(List.of(firstMessage, secondMessage));

    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(appraisalMessageHandler, importConfirmationMessageHandler);
  }

  @Test
  void handleRequestShouldRetainCheckedServiceFailureAsCause() throws Exception {
    var event = event("{\"user\":\"seller\",\"import_id\":\"import-123\",\"type\":\"confirm\"}");
    var failure = new NotFoundException("missing import");
    doThrow(failure).when(importConfirmationMessageHandler).handleMessage(any(), any());

    assertThatThrownBy(() -> handler.handleRequest(event, null))
        .isInstanceOf(RuntimeException.class)
        .hasCause(failure);
  }

  @Test
  void handleRequestShouldRetainRuntimeFailureAsCause() throws Exception {
    var event = event("{\"user\":\"seller\",\"import_id\":\"import-123\",\"type\":\"confirm\"}");
    var failure = new IllegalStateException("storage failed");
    doThrow(failure).when(importConfirmationMessageHandler).handleMessage(any(), any());

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
