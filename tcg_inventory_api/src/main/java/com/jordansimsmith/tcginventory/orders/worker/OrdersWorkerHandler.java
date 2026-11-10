package com.jordansimsmith.tcginventory.orders.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.orders.server.FulfillmentMessage;
import com.jordansimsmith.tcginventory.orders.server.FulfillmentMessageHandler;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OrdersWorkerHandler implements RequestHandler<SQSEvent, Void> {
  private static final Logger LOGGER = LoggerFactory.getLogger(OrdersWorkerHandler.class);

  private final ObjectMapper objectMapper;
  private final FulfillmentMessageHandler fulfillmentMessageHandler;

  public OrdersWorkerHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  public OrdersWorkerHandler(TcgInventoryFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.fulfillmentMessageHandler = factory.fulfillmentMessageHandler();
  }

  @Override
  public Void handleRequest(SQSEvent event, Context context) {
    try {
      doHandleRequest(event);
      return null;
    } catch (Exception e) {
      LOGGER.error("orders worker invocation failed: {}", e.getClass().getSimpleName());
      throw new RuntimeException(e);
    }
  }

  private void doHandleRequest(SQSEvent event) throws IOException, ServiceException {
    if (event.getRecords().size() != 1) {
      throw new IllegalArgumentException(
          "expected one SQS record, got " + event.getRecords().size());
    }

    var message =
        objectMapper.readValue(event.getRecords().getFirst().getBody(), FulfillmentMessage.class);
    fulfillmentMessageHandler.handleMessage(new RequestContext(message.user()), message);
  }
}
