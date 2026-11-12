package com.jordansimsmith.tcginventory.imports.server;

import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceRequest;
import com.jordansimsmith.tcginventory.pricing.client.PricingService;
import com.jordansimsmith.time.Clock;

public class AppraisalMessageHandler {
  public static final int BATCH_SIZE = 100;

  private final ImportReadRepository importReadRepository;
  private final ImportMutationRepository importMutationRepository;
  private final AppraisalResolverFactory appraisalResolverFactory;
  private final PricingService pricingService;
  private final AppraisalPolicy appraisalPolicy;
  private final QueueClient<ImportMessage> importsQueue;
  private final Clock clock;

  public AppraisalMessageHandler(
      ImportReadRepository importReadRepository,
      ImportMutationRepository importMutationRepository,
      AppraisalResolverFactory appraisalResolverFactory,
      PricingService pricingService,
      AppraisalPolicy appraisalPolicy,
      QueueClient<ImportMessage> importsQueue,
      Clock clock) {
    this.importReadRepository = importReadRepository;
    this.importMutationRepository = importMutationRepository;
    this.appraisalResolverFactory = appraisalResolverFactory;
    this.pricingService = pricingService;
    this.appraisalPolicy = appraisalPolicy;
    this.importsQueue = importsQueue;
    this.clock = clock;
  }

  public void handleMessage(RequestContext ctx, ImportMessage message) throws ServiceException {
    if (message.type() != ImportMessageType.APPRAISE) {
      throw new IllegalArgumentException("message is not an appraisal message");
    }
    var importItem = importReadRepository.getImport(ctx.user(), message.importId());
    if (importItem == null) {
      throw new IllegalStateException("appraisal import not found");
    }
    if ("review".equals(importItem.getStatus())
        || "confirming".equals(importItem.getStatus())
        || "confirmed".equals(importItem.getStatus())) {
      return;
    }
    if (!"appraising".equals(importItem.getStatus())) {
      throw new IllegalStateException("appraisal import has an unknown status");
    }

    var previousPosition = importItem.getAppraisedRowCount();
    var rowCount = importItem.getRowCount();
    var position = Math.min(previousPosition + BATCH_SIZE, rowCount);
    var resolver = appraisalResolverFactory.forGame(importItem.getGame());
    for (var row :
        importReadRepository.findAppraisalRows(
            ctx.user(), message.importId(), previousPosition, position)) {
      if (row.getDecision() != null) {
        continue;
      }
      var result = resolver.resolve(ctx, row);
      String decision;
      var reason = result.notes();
      String marketPrice = null;
      String suggestedPrice = null;
      String cardId = null;
      Integer setId = null;
      if (result.decision() == AppraisalResult.Decision.REVIEW) {
        decision = "review";
      } else {
        var price =
            pricingService.getPrice(
                ctx, new GetPriceRequest(result.cardId(), Condition.valueOf(row.getCondition())));
        marketPrice = price.marketPrice().toPlainString();
        if (appraisalPolicy.decide(price.marketPrice()) == AppraisalPolicy.Decision.DISCARD) {
          decision = "discard";
          reason = "below threshold";
        } else {
          decision = "keep";
          suggestedPrice = price.suggestedPrice().toPlainString();
          cardId = result.cardId();
          setId = result.setId();
        }
      }
      importMutationRepository.recordAppraisal(
          ctx.user(),
          message.importId(),
          row.getPosition(),
          decision,
          reason,
          marketPrice,
          suggestedPrice,
          cardId,
          setId);
    }

    if (position == rowCount) {
      importMutationRepository.finishAppraisal(
          ctx.user(), message.importId(), previousPosition, position, clock.now());
      return;
    }
    importMutationRepository.checkpointAppraisal(
        ctx.user(), message.importId(), previousPosition, position, clock.now());
    var continuation =
        new ImportMessage(message.user(), message.importId(), ImportMessageType.APPRAISE);
    importsQueue.send(
        continuation, message.importId(), message.importId() + "#appraise#" + position);
  }
}
