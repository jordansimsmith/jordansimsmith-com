package com.jordansimsmith.tcginventory.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.JobProcessor;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgTokenMinter;
import com.jordansimsmith.tcginventory.orders.OrderPhaseProcessor;
import java.time.Instant;
import org.junit.jupiter.api.Test;

public class PublishJobProcessorTest {
  private static final String USER = "alice";

  @Test
  void processBatchReturnsSafeTerminalServiceFailuresWithoutPublishing() throws Exception {
    var tokenMinter = mock(FetchTcgTokenMinter.class);
    var orderPhaseProcessor = mock(OrderPhaseProcessor.class);
    var listingPhaseProcessor = mock(ListingPhaseProcessor.class);
    doThrow(new BadRequestException("Refresh token is invalid"))
        .when(orderPhaseProcessor)
        .process(USER);
    var processor =
        new PublishJobProcessor(tokenMinter, orderPhaseProcessor, listingPhaseProcessor);

    var result = processor.processBatch(USER, jobItem(null));

    assertThat(result).isEqualTo(new JobProcessor.FailureJobResult("Refresh token is invalid"));
    verifyNoInteractions(tokenMinter, listingPhaseProcessor);
  }

  @Test
  void processBatchPropagatesUnavailableServiceFailuresForQueueRetry() throws Exception {
    var tokenMinter = mock(FetchTcgTokenMinter.class);
    var orderPhaseProcessor = mock(OrderPhaseProcessor.class);
    var listingPhaseProcessor = mock(ListingPhaseProcessor.class);
    var serviceFailure =
        new ServiceUnavailableException("FetchTCG seller offers are temporarily unavailable");
    doThrow(serviceFailure).when(orderPhaseProcessor).process(USER);
    var processor =
        new PublishJobProcessor(tokenMinter, orderPhaseProcessor, listingPhaseProcessor);

    assertThatThrownBy(() -> processor.processBatch(USER, jobItem(null)))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("FetchTCG offers lookup failed")
        .hasCause(serviceFailure);
    verifyNoInteractions(tokenMinter, listingPhaseProcessor);
  }

  @Test
  void processBatchMintsLegacyListingBearerAfterSuccessfulOrderFetching() throws Exception {
    var tokenMinter = mock(FetchTcgTokenMinter.class);
    var orderPhaseProcessor = mock(OrderPhaseProcessor.class);
    var listingPhaseProcessor = mock(ListingPhaseProcessor.class);
    var success = new JobProcessor.SuccessJobResult(1, null);
    when(tokenMinter.mint(USER)).thenReturn("legacy-bearer");
    when(listingPhaseProcessor.process(USER, "legacy-bearer", 0)).thenReturn(success);
    var processor =
        new PublishJobProcessor(tokenMinter, orderPhaseProcessor, listingPhaseProcessor);

    var result = processor.processBatch(USER, jobItem(null));

    assertThat(result).isSameAs(success);
    var inOrder = inOrder(orderPhaseProcessor, tokenMinter, listingPhaseProcessor);
    inOrder.verify(orderPhaseProcessor).process(USER);
    inOrder.verify(tokenMinter).mint(USER);
    inOrder.verify(listingPhaseProcessor).process(USER, "legacy-bearer", 0);
  }

  private static JobItem jobItem(String continuation) {
    var item = JobItem.create(USER, "publish-job", "publish", null, null, Instant.EPOCH);
    item.setContinuation(continuation);
    return item;
  }
}
