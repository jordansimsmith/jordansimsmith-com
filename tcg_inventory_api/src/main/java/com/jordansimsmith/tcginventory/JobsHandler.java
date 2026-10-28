package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.tcginventory.catalog.CatalogFactory;
import com.jordansimsmith.tcginventory.imports.AppraiseJobProcessor;
import com.jordansimsmith.tcginventory.imports.ImportConfirmationJobProcessor;
import com.jordansimsmith.tcginventory.imports.ImportItem;
import com.jordansimsmith.tcginventory.imports.ImportRowItem;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.tcginventory.orders.OrderFulfillmentJobProcessor;
import com.jordansimsmith.tcginventory.orders.OrderItem;
import com.jordansimsmith.tcginventory.orders.OrderPhaseProcessor;
import com.jordansimsmith.tcginventory.orders.OrderRepository;
import com.jordansimsmith.tcginventory.pricing.PricingRepository;
import com.jordansimsmith.tcginventory.pricing.RepriceJobProcessor;
import com.jordansimsmith.tcginventory.publish.ListingPhaseProcessor;
import com.jordansimsmith.tcginventory.publish.PublishJobProcessor;
import com.jordansimsmith.tcginventory.reports.ReportJobProcessor;
import com.jordansimsmith.tcginventory.scans.ScanConfirmationJobProcessor;
import com.jordansimsmith.tcginventory.settings.SettingsItem;
import com.jordansimsmith.time.Clock;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class JobsHandler implements RequestHandler<SQSEvent, Void> {

  private static final Logger LOGGER = LoggerFactory.getLogger(JobsHandler.class);
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final DynamoDbTable<JobItem> jobTable;
  private final QueueClient<JobMessage> jobsQueue;
  private final JobProcessor appraiseJobProcessor;
  private final JobProcessor publishJobProcessor;
  private final JobProcessor reportJobProcessor;
  private final JobProcessor scanConfirmationJobProcessor;
  private final JobProcessor importConfirmationJobProcessor;
  private final JobProcessor orderFulfillmentJobProcessor;
  private final JobProcessor repriceJobProcessor;

  public JobsHandler() {
    this(CatalogFactory.create());
  }

  @VisibleForTesting
  public JobsHandler(CatalogFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.clock = factory.clock();
    this.jobTable = factory.jobTable();
    this.jobsQueue = factory.jobsQueue();
    var enhancedClient = factory.dynamoDbEnhancedClient();
    var pricingRepository = new PricingRepository(factory);
    this.appraiseJobProcessor =
        new AppraiseJobProcessor(
            TcgInventoryTable.table(enhancedClient, ImportItem.class),
            TcgInventoryTable.table(enhancedClient, ImportRowItem.class),
            factory.clock(),
            factory.fetchTcgService(),
            pricingRepository);
    var publishDynamoDbClient = factory.dynamoDbClient();
    var publishClock = factory.clock();
    var publishUlidGenerator = factory.ulidGenerator();
    var orderTable = TcgInventoryTable.table(enhancedClient, OrderItem.class);
    var skuTable = TcgInventoryTable.table(enhancedClient, SkuItem.class);
    var inventoryRepository =
        new InventoryRepository(
            TcgInventoryTable.table(enhancedClient, UnitItem.class),
            publishDynamoDbClient,
            publishClock,
            publishUlidGenerator);
    var orderRepository =
        new OrderRepository(
            orderTable,
            factory.jobTable(),
            inventoryRepository,
            publishDynamoDbClient,
            publishClock);
    var orderPhaseProcessor =
        new OrderPhaseProcessor(
            orderTable,
            skuTable,
            TcgInventoryTable.table(enhancedClient, SettingsItem.class),
            orderRepository,
            inventoryRepository,
            publishClock,
            factory.fetchTcgService());
    var listingPhaseProcessor =
        new ListingPhaseProcessor(
            skuTable,
            inventoryRepository,
            publishDynamoDbClient,
            publishClock,
            factory.fetchTcgService(),
            factory.s3Client());
    this.publishJobProcessor = new PublishJobProcessor(orderPhaseProcessor, listingPhaseProcessor);
    this.reportJobProcessor = new ReportJobProcessor(factory);
    this.scanConfirmationJobProcessor = new ScanConfirmationJobProcessor(factory);
    this.importConfirmationJobProcessor = new ImportConfirmationJobProcessor(factory);
    this.orderFulfillmentJobProcessor = new OrderFulfillmentJobProcessor(factory);
    var ulidGenerator = factory.ulidGenerator();
    this.repriceJobProcessor =
        new RepriceJobProcessor(
            TcgInventoryTable.table(enhancedClient, SkuItem.class),
            new InventoryRepository(
                TcgInventoryTable.table(enhancedClient, UnitItem.class),
                factory.dynamoDbClient(),
                factory.clock(),
                ulidGenerator),
            factory.objectMapper(),
            factory.fetchTcgService(),
            pricingRepository,
            factory.clock(),
            ulidGenerator);
  }

  @Override
  public Void handleRequest(SQSEvent event, Context context) {
    doHandleRequest(event);
    return null;
  }

  private void doHandleRequest(SQSEvent event) {
    var record = event.getRecords().get(0);
    var message = readMessage(record.getBody());

    var jobKey =
        Key.builder()
            .partitionValue(JobItem.formatPk(message.user()))
            .sortValue(JobItem.formatSk(message.jobId()))
            .build();

    var jobItem = jobTable.getItem(request -> request.key(jobKey).consistentRead(true));
    if (jobItem == null) {
      throw new IllegalStateException("job item not found: " + message.jobId());
    }

    var status = jobItem.getStatus();
    if ("succeeded".equals(status) || "failed".equals(status)) {
      LOGGER.info("duplicate delivery for completed job: {} ({})", message.jobId(), status);
      return;
    }

    if ("queued".equals(status)) {
      jobItem.setStatus("running");
      jobItem.setProcessedCount(0);
      jobItem.setUpdatedAt(clock.now());
      jobTable.putItem(jobItem);
    }

    var processor = jobProcessor(message.jobType());
    var result = processor.processBatch(message.user(), jobItem);
    if (result instanceof JobProcessor.FailureJobResult failure) {
      jobItem.setStatus("failed");
      jobItem.setError(failure.error());
      jobItem.setUpdatedAt(clock.now());
      jobTable.putItem(jobItem);
      return;
    }

    var batchResult = (JobProcessor.SuccessJobResult) result;
    processBatch(message, jobItem, batchResult);
  }

  private JobMessage readMessage(String body) {
    try {
      return objectMapper.readValue(body, JobMessage.class);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void processBatch(
      JobMessage message, JobItem jobItem, JobProcessor.SuccessJobResult result) {
    var previousProcessedCount = jobItem.getProcessedCount();

    // the processed count deduplication id only distinguishes batches when every
    // re-enqueueing batch advances; a non-advancing batch would be silently
    // deduplicated into a stalled job, so fail loudly instead
    if (result.continuation() != null && result.processedCount() <= previousProcessedCount) {
      throw new IllegalStateException(
          "job processed count did not advance: "
              + previousProcessedCount
              + " -> "
              + result.processedCount());
    }

    jobItem.setContinuation(result.continuation());
    jobItem.setProcessedCount(result.processedCount());
    jobItem.setUpdatedAt(clock.now());

    if (result.continuation() == null) {
      jobItem.setStatus("succeeded");
    }
    jobTable.putItem(jobItem);

    if (result.continuation() != null) {
      jobsQueue.send(message, message.user(), message.deduplicationId(result.processedCount()));
    }
  }

  private JobProcessor jobProcessor(String jobType) {
    return switch (jobType) {
      case "appraise" -> appraiseJobProcessor;
      case "publish" -> publishJobProcessor;
      case "report" -> reportJobProcessor;
      case "scan_confirmation" -> scanConfirmationJobProcessor;
      case "import_confirmation" -> importConfirmationJobProcessor;
      case "order_fulfillment" -> orderFulfillmentJobProcessor;
      case "reprice" -> repriceJobProcessor;
      default -> throw new IllegalArgumentException("unknown job type: " + jobType);
    };
  }
}
