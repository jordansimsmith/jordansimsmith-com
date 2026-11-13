package com.jordansimsmith.tcginventory.scans.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.scans.server.ScanConfirmationMessage;
import com.jordansimsmith.tcginventory.scans.server.ScanConfirmationMessageHandler;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScanWorkerHandlerTest {
  private static final String BODY =
      """
      {"user":"jordan","scan_id":"scan-1"}
      """
          .strip();
  private static final RequestContext CTX = new RequestContext("jordan");
  private static final ScanConfirmationMessage MESSAGE =
      new ScanConfirmationMessage("jordan", "scan-1");

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ScanConfirmationMessageHandler confirmation =
      mock(ScanConfirmationMessageHandler.class);
  private final ScanWorkerHandler handler = new ScanWorkerHandler(objectMapper, confirmation);

  @Test
  void handleRequestShouldDelegateWithMessageUser() throws Exception {
    // arrange
    var event = event(BODY);

    // act
    var response = handler.handleRequest(event, null);

    // assert
    assertThat(response).isNull();
    assertThat(objectMapper.writeValueAsString(MESSAGE)).isEqualTo(BODY);
    verify(confirmation).handleMessage(CTX, MESSAGE);
  }

  @Test
  void handleRequestShouldRejectEventsWithoutExactlyOneRecord() {
    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(event(), null))
        .hasMessage("expected one SQS record, got 0");
    assertThatThrownBy(() -> handler.handleRequest(event(BODY, BODY), null))
        .hasMessage("expected one SQS record, got 2");
    verifyNoInteractions(confirmation);
  }

  @Test
  void handleRequestShouldFailWhenConfirmationFails() throws Exception {
    // arrange
    var failure = new ServiceUnavailableException("catalog unavailable");
    doThrow(failure).when(confirmation).handleMessage(CTX, MESSAGE);

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(event(BODY), null)).hasCause(failure);
  }

  private SQSEvent event(String... bodies) {
    var event = new SQSEvent();
    event.setRecords(
        List.of(bodies).stream()
            .map(
                body -> {
                  var record = new SQSEvent.SQSMessage();
                  record.setBody(body);
                  return record;
                })
            .toList());
    return event;
  }
}
