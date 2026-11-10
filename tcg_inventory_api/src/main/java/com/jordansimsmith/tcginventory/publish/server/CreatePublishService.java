package com.jordansimsmith.tcginventory.publish.server;

import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.publish.client.CreatePublishRequest;
import com.jordansimsmith.tcginventory.publish.client.CreatePublishResponse;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;

public class CreatePublishService {
  private final PublishRepository publishRepository;
  private final QueueClient<PublishMessage> publishQueue;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;

  public CreatePublishService(
      PublishRepository publishRepository,
      QueueClient<PublishMessage> publishQueue,
      Clock clock,
      UlidGenerator ulidGenerator) {
    this.publishRepository = publishRepository;
    this.publishQueue = publishQueue;
    this.clock = clock;
    this.ulidGenerator = ulidGenerator;
  }

  public CreatePublishResponse createPublish(RequestContext ctx, CreatePublishRequest request)
      throws ServiceException {
    var publish = publishRepository.get(ctx.user());
    if (publish != null
        && ("queued".equals(publish.getStatus()) || "running".equals(publish.getStatus()))) {
      return new CreatePublishResponse();
    }

    var publishId = ulidGenerator.generate();
    publishRepository.createPublish(ctx.user(), publishId, clock.now());
    publishQueue.send(new PublishMessage(ctx.user(), publishId), ctx.user(), publishId + "#0");
    return new CreatePublishResponse();
  }
}
