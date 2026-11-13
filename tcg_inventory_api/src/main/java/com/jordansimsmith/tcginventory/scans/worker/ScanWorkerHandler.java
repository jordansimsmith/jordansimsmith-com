package com.jordansimsmith.tcginventory.scans.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.scans.server.ScanConfirmationMessage;
import com.jordansimsmith.tcginventory.scans.server.ScanConfirmationMessageHandler;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScanWorkerHandler implements RequestHandler<SQSEvent, Void> {
  private static final Logger LOGGER = LoggerFactory.getLogger(ScanWorkerHandler.class);

  private final ObjectMapper objectMapper;
  private final ScanConfirmationMessageHandler messageHandler;

  public ScanWorkerHandler() {
    this(TcgInventoryFactory.create());
  }

  private ScanWorkerHandler(TcgInventoryFactory factory) {
    this(factory.objectMapper(), factory.scanConfirmationMessageHandler());
  }

  @VisibleForTesting
  ScanWorkerHandler(ObjectMapper objectMapper, ScanConfirmationMessageHandler messageHandler) {
    this.objectMapper = objectMapper;
    this.messageHandler = messageHandler;
  }

  @Override
  public Void handleRequest(SQSEvent event, Context context) {
    try {
      doHandleRequest(event);
      return null;
    } catch (IOException | ServiceException e) {
      LOGGER.error("scan confirmation invocation failed: {}", e.getClass().getSimpleName());
      throw new RuntimeException(e);
    }
  }

  private void doHandleRequest(SQSEvent event) throws IOException, ServiceException {
    if (event.getRecords().size() != 1) {
      throw new IllegalArgumentException(
          "expected one SQS record, got " + event.getRecords().size());
    }
    var message =
        objectMapper.readValue(
            event.getRecords().getFirst().getBody(), ScanConfirmationMessage.class);
    if (message.user() == null
        || message.user().isBlank()
        || message.scanId() == null
        || message.scanId().isBlank()) {
      throw new IllegalArgumentException("scan confirmation requires user and scan_id");
    }
    messageHandler.handleMessage(new RequestContext(message.user()), message);
  }
}
