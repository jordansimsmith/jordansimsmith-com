package com.jordansimsmith.tcginventory.publish;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.tcginventory.ActiveJob;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobMessage;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

public class CreatePublishHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(CreatePublishHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<JobItem> jobTable;
  private final ActiveJob activeJob;
  private final QueueClient<JobMessage> jobsQueue;
  private final UlidGenerator ulidGenerator;

  public CreatePublishHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  CreatePublishHandler(TcgInventoryFactory factory) {
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.jobTable = factory.jobTable();
    this.activeJob = new ActiveJob(jobTable);
    this.jobsQueue = factory.jobsQueue();
    this.ulidGenerator = factory.ulidGenerator();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing create publish request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();

    // preserve idempotent responses while the publish job is active.
    if (activeJob.exists(user, "publish")) {
      return httpResponseFactory.accepted();
    }
    // avoid overlapping jobs for the same user.
    if (activeJob.exists(user)) {
      return httpResponseFactory.conflict(new ErrorResponse("another job is in progress"));
    }

    var now = clock.now();
    var jobId = ulidGenerator.generate();

    var jobItem = JobItem.create(user, jobId, "publish", null, now);
    jobTable.putItem(jobItem);

    var jobMessage = new JobMessage(user, jobId, "publish");
    jobsQueue.send(jobMessage, user, jobMessage.deduplicationId(0));

    return httpResponseFactory.accepted();
  }
}
