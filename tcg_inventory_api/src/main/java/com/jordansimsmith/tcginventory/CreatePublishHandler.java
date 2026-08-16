package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class CreatePublishHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(CreatePublishHandler.class);

  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
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
    this.tcgInventoryTable = factory.tcgInventoryTable();
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

    var activeJob = findActivePublishJob(user);
    if (activeJob != null) {
      return httpResponseFactory.accepted();
    }

    var now = clock.now();
    var jobId = ulidGenerator.generate();

    var jobItem = TcgInventoryItem.createJob(user, jobId, "publish", null, now);
    tcgInventoryTable.putItem(jobItem);

    jobsQueue.send(new JobMessage(user, jobId, "publish"), user);

    return httpResponseFactory.accepted();
  }

  private TcgInventoryItem findActivePublishJob(String user) {
    var queryConditional =
        QueryConditional.sortBeginsWith(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk(user))
                .sortValue(TcgInventoryItem.JOB_PREFIX)
                .build());

    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(queryConditional)
            .scanIndexForward(false)
            .build();

    return tcgInventoryTable.query(request).stream()
        .flatMap(page -> page.items().stream())
        .filter(item -> "publish".equals(item.getJobType()))
        .filter(item -> "queued".equals(item.getStatus()) || "running".equals(item.getStatus()))
        .findFirst()
        .orElse(null);
  }
}
