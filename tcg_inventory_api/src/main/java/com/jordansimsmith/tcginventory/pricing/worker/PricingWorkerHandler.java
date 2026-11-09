package com.jordansimsmith.tcginventory.pricing.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.pricing.server.RepricingMessage;
import com.jordansimsmith.tcginventory.pricing.server.RepricingMessageHandler;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PricingWorkerHandler implements RequestHandler<SQSEvent, Void> {
  private static final Logger LOGGER = LoggerFactory.getLogger(PricingWorkerHandler.class);

  private final ObjectMapper objectMapper;
  private final RepricingMessageHandler repricingMessageHandler;

  public PricingWorkerHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public PricingWorkerHandler(TcgInventoryFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.repricingMessageHandler = factory.repricingMessageHandler();
  }

  @Override
  public Void handleRequest(SQSEvent event, Context context) {
    try {
      doHandleRequest(event);
      return null;
    } catch (Exception e) {
      LOGGER.error("pricing worker invocation failed: {}", e.getClass().getSimpleName());
      throw new RuntimeException(e);
    }
  }

  private void doHandleRequest(SQSEvent event) throws IOException, ServiceException {
    if (event.getRecords().size() != 1) {
      throw new IllegalArgumentException(
          "expected one SQS record, got " + event.getRecords().size());
    }

    var message =
        objectMapper.readValue(event.getRecords().getFirst().getBody(), RepricingMessage.class);
    repricingMessageHandler.handleMessage(new RequestContext(message.user()), message);
  }
}
