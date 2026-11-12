package com.jordansimsmith.tcginventory.imports.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.imports.server.AppraisalMessageHandler;
import com.jordansimsmith.tcginventory.imports.server.ImportConfirmationMessageHandler;
import com.jordansimsmith.tcginventory.imports.server.ImportMessage;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImportsWorkerHandler implements RequestHandler<SQSEvent, Void> {
  private static final Logger LOGGER = LoggerFactory.getLogger(ImportsWorkerHandler.class);

  private final ObjectMapper objectMapper;
  private final AppraisalMessageHandler appraisalMessageHandler;
  private final ImportConfirmationMessageHandler importConfirmationMessageHandler;

  public ImportsWorkerHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public ImportsWorkerHandler(TcgInventoryFactory factory) {
    this(
        factory.objectMapper(),
        factory.appraisalMessageHandler(),
        factory.importConfirmationMessageHandler());
  }

  @VisibleForTesting
  ImportsWorkerHandler(
      ObjectMapper objectMapper,
      AppraisalMessageHandler appraisalMessageHandler,
      ImportConfirmationMessageHandler importConfirmationMessageHandler) {
    this.objectMapper = objectMapper;
    this.appraisalMessageHandler = appraisalMessageHandler;
    this.importConfirmationMessageHandler = importConfirmationMessageHandler;
  }

  @Override
  public Void handleRequest(SQSEvent event, Context context) {
    try {
      doHandleRequest(event);
      return null;
    } catch (Exception e) {
      LOGGER.error("imports worker invocation failed: {}", e.getClass().getSimpleName());
      throw new RuntimeException(e);
    }
  }

  private void doHandleRequest(SQSEvent event) throws IOException, ServiceException {
    if (event.getRecords().size() != 1) {
      throw new IllegalArgumentException(
          "expected one SQS record, got " + event.getRecords().size());
    }

    var message =
        objectMapper.readValue(event.getRecords().getFirst().getBody(), ImportMessage.class);
    if (message.type() == null) {
      throw new IllegalArgumentException("import message type is required");
    }

    var requestContext = new RequestContext(message.user());
    switch (message.type()) {
      case APPRAISE -> appraisalMessageHandler.handleMessage(requestContext, message);
      case CONFIRM -> importConfirmationMessageHandler.handleMessage(requestContext, message);
    }
  }
}
