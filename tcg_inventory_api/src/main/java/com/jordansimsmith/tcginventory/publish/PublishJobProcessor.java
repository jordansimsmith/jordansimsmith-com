package com.jordansimsmith.tcginventory.publish;

import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgAuthException;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgTokenMinter;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.tcginventory.orders.OrderItem;
import com.jordansimsmith.tcginventory.orders.OrderPhaseProcessor;
import com.jordansimsmith.tcginventory.orders.OrderRepository;
import com.jordansimsmith.tcginventory.settings.SettingsItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PublishJobProcessor implements JobProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(PublishJobProcessor.class);

  private final FetchTcgTokenMinter fetchTcgTokenMinter;
  private final OrderPhaseProcessor orderPhaseProcessor;
  private final ListingPhaseProcessor listingPhaseProcessor;

  public PublishJobProcessor(TcgInventoryFactory factory) {
    var enhancedClient = factory.dynamoDbEnhancedClient();
    var dynamoDbClient = factory.dynamoDbClient();
    var clock = factory.clock();
    var unitTable = TcgInventoryTable.table(enhancedClient, UnitItem.class);
    var orderTable = TcgInventoryTable.table(enhancedClient, OrderItem.class);
    var skuTable = TcgInventoryTable.table(enhancedClient, SkuItem.class);
    var inventoryRepository =
        new InventoryRepository(unitTable, dynamoDbClient, clock, factory.ulidGenerator());
    var orderRepository =
        new OrderRepository(orderTable, inventoryRepository, dynamoDbClient, clock);

    this.fetchTcgTokenMinter = factory.fetchTcgTokenMinter();
    this.orderPhaseProcessor =
        new OrderPhaseProcessor(
            orderTable,
            skuTable,
            TcgInventoryTable.table(enhancedClient, SettingsItem.class),
            orderRepository,
            clock,
            factory.fetchTcgClient());
    this.listingPhaseProcessor =
        new ListingPhaseProcessor(
            skuTable,
            inventoryRepository,
            dynamoDbClient,
            clock,
            factory.fetchTcgClient(),
            factory.s3Client());
  }

  @Override
  public JobProcessor.JobResult processBatch(String user, JobItem jobItem) {
    try {
      return doProcessBatch(user, jobItem);
    } catch (FetchTcgAuthException e) {
      return new JobProcessor.FailureJobResult(FetchTcgAuthException.USER_MESSAGE);
    }
  }

  private JobProcessor.SuccessJobResult doProcessBatch(String user, JobItem jobItem) {
    LOGGER.info("starting publish job for user {}", user);
    var bearerToken = fetchTcgTokenMinter.mint(user);
    LOGGER.info("minted FetchTCG bearer token");

    var continuation = jobItem.getContinuation() != null ? jobItem.getContinuation() : 0;

    if (continuation == 0) {
      LOGGER.info("running order phase (continuation=0)");
      orderPhaseProcessor.process(user, bearerToken);
      LOGGER.info("order phase complete");
    } else {
      LOGGER.info("skipping order phase (continuation={})", continuation);
    }

    LOGGER.info("running listing phase");
    var result = listingPhaseProcessor.process(user, bearerToken, continuation);
    LOGGER.info(
        "listing phase complete: processedUpTo={}, complete={}",
        result.processedUpTo(),
        result.complete());
    return result;
  }
}
