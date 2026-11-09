package com.jordansimsmith.tcginventory.pricing.server;

import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.pricing.client.CreateRepricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.CreateRepricingResponse;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;

public class CreateRepricingService {
  private final RepricingRepository repricingRepository;
  private final QueueClient<RepricingMessage> pricingQueue;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;

  public CreateRepricingService(
      RepricingRepository repricingRepository,
      QueueClient<RepricingMessage> pricingQueue,
      Clock clock,
      UlidGenerator ulidGenerator) {
    this.repricingRepository = repricingRepository;
    this.pricingQueue = pricingQueue;
    this.clock = clock;
    this.ulidGenerator = ulidGenerator;
  }

  public CreateRepricingResponse createRepricing(RequestContext ctx, CreateRepricingRequest request)
      throws ServiceException {
    var run = repricingRepository.get(ctx.user());
    if (run != null && ("queued".equals(run.getStatus()) || "running".equals(run.getStatus()))) {
      return new CreateRepricingResponse();
    }

    var repricingId = ulidGenerator.generate();
    repricingRepository.createRepricing(ctx.user(), repricingId, clock.now());
    pricingQueue.send(new RepricingMessage(ctx.user(), repricingId), repricingId, repricingId);
    return new CreateRepricingResponse();
  }
}
