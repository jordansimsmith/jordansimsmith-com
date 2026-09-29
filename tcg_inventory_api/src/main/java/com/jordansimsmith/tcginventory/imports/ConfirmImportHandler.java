package com.jordansimsmith.tcginventory.imports;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.tcginventory.JobMessage;
import com.jordansimsmith.tcginventory.Photos;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfirmImportHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfirmImportHandler.class);

  record ConfirmImportResponse(@JsonProperty("import_id") String importId) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final ImportRepository importRepository;
  private final QueueClient<JobMessage> jobsQueue;

  public ConfirmImportHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  ConfirmImportHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    var dynamoDbClient = factory.dynamoDbClient();
    var inventoryRepository =
        new InventoryRepository(
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), UnitItem.class),
            dynamoDbClient,
            factory.clock(),
            factory.ulidGenerator());
    this.importRepository =
        new ImportRepository(
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportItem.class),
            TcgInventoryTable.table(factory.dynamoDbEnhancedClient(), ImportRowItem.class),
            factory.jobTable(),
            inventoryRepository,
            dynamoDbClient,
            factory.clock());
    this.jobsQueue = factory.jobsQueue();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing confirm import request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var importId = event.getPathParameters().get("import_id");
    var importItem = importRepository.getImport(user, importId);
    if (importItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }
    if ("confirmed".equals(importItem.getStatus())) {
      return httpResponseFactory.ok(new ConfirmImportResponse(importId));
    }
    if ("review".equals(importItem.getStatus())) {
      var keepRows = importRepository.findKeepRows(user, importId);
      var rowsNeedingPhotos =
          keepRows.stream()
              .filter(
                  row ->
                      Photos.needsPhotos(
                          row.getDecision(),
                          row.getSuggestedPrice(),
                          row.getPhotos() == null ? 0 : row.getPhotos().size()))
              .count();
      if (rowsNeedingPhotos > 0) {
        return httpResponseFactory.conflict(
            new ErrorResponse(rowsNeedingPhotos + " rows need photos before confirm"));
      }
    }
    if (!"review".equals(importItem.getStatus()) && !"confirming".equals(importItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("import is not in review status"));
    }

    var job = importRepository.ensureConfirmationJob(user, importId);
    importItem = importRepository.getImport(user, importId);
    if ("confirmed".equals(importItem.getStatus())) {
      return httpResponseFactory.ok(new ConfirmImportResponse(importId));
    }
    if (job == null) {
      throw new IllegalStateException("confirming import is missing its confirmation job");
    }
    var jobMessage = new JobMessage(user, job.getJobId(), "confirm_import");
    var continuation = job.getContinuation() == null ? 0 : job.getContinuation();
    jobsQueue.send(jobMessage, user, jobMessage.deduplicationId(continuation));
    return httpResponseFactory.accepted(new ConfirmImportResponse(importId));
  }
}
