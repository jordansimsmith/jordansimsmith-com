package com.jordansimsmith.tcginventory.publish;

import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.orders.OrderPhaseProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PublishJobProcessor implements JobProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(PublishJobProcessor.class);

  private final OrderPhaseProcessor orderPhaseProcessor;
  private final ListingPhaseProcessor listingPhaseProcessor;

  public PublishJobProcessor(
      OrderPhaseProcessor orderPhaseProcessor, ListingPhaseProcessor listingPhaseProcessor) {
    this.orderPhaseProcessor = orderPhaseProcessor;
    this.listingPhaseProcessor = listingPhaseProcessor;
  }

  @Override
  public JobProcessor.JobResult processBatch(String user, JobItem jobItem) {
    try {
      return doProcessBatch(user, jobItem);
    } catch (ServiceUnavailableException e) {
      throw new RuntimeException("FetchTCG request failed", e);
    } catch (ServiceException e) {
      return new JobProcessor.FailureJobResult(e.getMessage());
    } catch (OrderPhaseProcessor.ReservationPreflightException e) {
      return new JobProcessor.FailureJobResult(e.getMessage());
    }
  }

  private JobProcessor.SuccessJobResult doProcessBatch(String user, JobItem jobItem)
      throws ServiceException {
    LOGGER.info("starting publish job for user {}", user);

    var continuation =
        jobItem.getContinuation() != null ? Integer.parseInt(jobItem.getContinuation()) : 0;

    if (continuation == 0) {
      LOGGER.info("running order phase (continuation=0)");
      orderPhaseProcessor.process(user);
      LOGGER.info("order phase complete");
    } else {
      LOGGER.info("skipping order phase (continuation={})", continuation);
    }

    LOGGER.info("running listing phase");
    var result = listingPhaseProcessor.process(user, continuation);
    LOGGER.info(
        "listing phase complete: processedCount={}, complete={}",
        result.processedCount(),
        result.continuation() == null);
    return result;
  }
}
