package com.jordansimsmith.tcginventory.catalog.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.catalog.server.CatalogRefreshMessage;
import com.jordansimsmith.tcginventory.catalog.server.CatalogRefreshMessageHandler;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CatalogWorkerHandler implements RequestHandler<SQSEvent, Void> {
  private static final Logger LOGGER = LoggerFactory.getLogger(CatalogWorkerHandler.class);

  private final ObjectMapper objectMapper;
  private final CatalogRefreshMessageHandler messageHandler;

  public CatalogWorkerHandler() {
    this(TcgInventoryFactory.create());
  }

  private CatalogWorkerHandler(TcgInventoryFactory factory) {
    this(factory.objectMapper(), factory.catalogRefreshMessageHandler());
  }

  @VisibleForTesting
  CatalogWorkerHandler(ObjectMapper objectMapper, CatalogRefreshMessageHandler messageHandler) {
    this.objectMapper = objectMapper;
    this.messageHandler = messageHandler;
  }

  @Override
  public Void handleRequest(SQSEvent event, Context context) {
    try {
      doHandleRequest(event);
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.error("catalog refresh was interrupted", e);
      throw new RuntimeException(e);
    } catch (IOException | RuntimeException e) {
      LOGGER.error("catalog refresh failed", e);
      throw new RuntimeException(e);
    }
  }

  private void doHandleRequest(SQSEvent event) throws IOException, InterruptedException {
    if (event.getRecords().size() != 1) {
      throw new IllegalArgumentException(
          "expected one SQS record, got " + event.getRecords().size());
    }

    var message =
        objectMapper.readValue(
            event.getRecords().getFirst().getBody(), CatalogRefreshMessage.class);
    messageHandler.handleMessage(message);
  }
}
