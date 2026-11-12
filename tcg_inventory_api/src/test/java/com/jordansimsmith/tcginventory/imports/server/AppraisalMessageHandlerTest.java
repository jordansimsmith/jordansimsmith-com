package com.jordansimsmith.tcginventory.imports.server;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.pricing.client.FakePricingService;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceResponse;
import com.jordansimsmith.time.Clock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppraisalMessageHandlerTest {
  private static final String USER = "appraisal-user";
  private static final String IMPORT_ID = "opaque/import-id";
  private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);

  @Test
  void handlerPricesResolvedCardsAndAppliesTheInjectedPolicy() throws Exception {
    var importReadRepository = mock(ImportReadRepository.class);
    var importMutationRepository = mock(ImportMutationRepository.class);
    var importsQueue = new FakeQueueClient<ImportMessage>();
    var importItem = new ImportItem();
    importItem.setGame("mtg");
    importItem.setStatus("appraising");
    importItem.setRowCount(2);
    importItem.setAppraisedRowCount(0);
    when(importReadRepository.getImport(USER, IMPORT_ID)).thenReturn(importItem);
    when(importReadRepository.findAppraisalRows(USER, IMPORT_ID, 0, 2))
        .thenReturn(List.of(row(1), row(2)));

    var pricingService = new FakePricingService();
    pricingService.seedPrice(
        USER,
        "below-threshold/card",
        Condition.NM,
        new GetPriceResponse(new BigDecimal("0.24"), new BigDecimal("1.25")));
    pricingService.seedPrice(
        USER,
        "at-threshold/card",
        Condition.NM,
        new GetPriceResponse(new BigDecimal("0.25"), new BigDecimal("1.25")));
    var resolver =
        (AppraisalResolver)
            (ctx, row) ->
                new AppraisalResult(
                    AppraisalResult.Decision.RESOLVED,
                    null,
                    row.getPosition() == 1 ? "below-threshold/card" : "at-threshold/card",
                    42);
    Clock clock = () -> NOW;
    var handler =
        new AppraisalMessageHandler(
            importReadRepository,
            importMutationRepository,
            new AppraisalResolverFactory(resolver),
            pricingService,
            new AppraisalPolicy(),
            importsQueue,
            clock);

    handler.handleMessage(
        new RequestContext(USER), new ImportMessage(USER, IMPORT_ID, ImportMessageType.APPRAISE));

    verify(importMutationRepository)
        .recordAppraisal(
            USER, IMPORT_ID, 1, "discard", "below threshold", "0.24", null, null, null);
    verify(importMutationRepository)
        .recordAppraisal(USER, IMPORT_ID, 2, "keep", null, "0.25", "1.25", "at-threshold/card", 42);
    verify(importMutationRepository).finishAppraisal(USER, IMPORT_ID, 0, 2, NOW);
  }

  private static ImportRowItem row(int position) {
    var row = new ImportRowItem();
    row.setPosition(position);
    row.setCondition("NM");
    return row;
  }
}
