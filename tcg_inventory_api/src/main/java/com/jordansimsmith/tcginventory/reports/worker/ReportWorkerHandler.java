package com.jordansimsmith.tcginventory.reports.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.reports.server.ReportMessage;
import com.jordansimsmith.tcginventory.reports.server.ReportMessageHandler;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReportWorkerHandler implements RequestHandler<SQSEvent, Void> {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReportWorkerHandler.class);

  private final ObjectMapper objectMapper;
  private final ReportMessageHandler reportMessageHandler;

  public ReportWorkerHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public ReportWorkerHandler(TcgInventoryFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.reportMessageHandler = factory.reportMessageHandler();
  }

  @Override
  public Void handleRequest(SQSEvent event, Context context) {
    try {
      doHandleRequest(event);
      return null;
    } catch (Exception e) {
      LOGGER.error("report worker invocation failed: {}", e.getClass().getSimpleName());
      throw new RuntimeException(e);
    }
  }

  private void doHandleRequest(SQSEvent event) throws IOException, ServiceException {
    if (event.getRecords().size() != 1) {
      throw new IllegalArgumentException(
          "expected one SQS record, got " + event.getRecords().size());
    }

    var message =
        objectMapper.readValue(event.getRecords().getFirst().getBody(), ReportMessage.class);
    reportMessageHandler.handleMessage(new RequestContext(message.user()), message);
  }
}
