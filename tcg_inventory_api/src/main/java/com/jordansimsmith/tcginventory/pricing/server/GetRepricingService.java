package com.jordansimsmith.tcginventory.pricing.server;

import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.pricing.client.GetRepricingRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetRepricingResponse;
import com.jordansimsmith.tcginventory.pricing.client.RepricingStatus;

public class GetRepricingService {
  private final RepricingRepository repricingRepository;

  public GetRepricingService(RepricingRepository repricingRepository) {
    this.repricingRepository = repricingRepository;
  }

  public GetRepricingResponse getRepricing(RequestContext ctx, GetRepricingRequest request)
      throws ServiceException {
    var run = repricingRepository.get(ctx.user());
    if (run == null) {
      throw new NotFoundException("Not Found");
    }

    var status =
        switch (run.getStatus()) {
          case "queued" -> RepricingStatus.QUEUED;
          case "running" -> RepricingStatus.RUNNING;
          case "succeeded" -> RepricingStatus.SUCCEEDED;
          default -> throw new IllegalStateException("Repricing run has an unknown status");
        };
    return new GetRepricingResponse(
        status,
        run.getProcessedSkuCount(),
        run.getStartedAt().getEpochSecond(),
        run.getFinishedAt() == null ? null : run.getFinishedAt().getEpochSecond());
  }
}
