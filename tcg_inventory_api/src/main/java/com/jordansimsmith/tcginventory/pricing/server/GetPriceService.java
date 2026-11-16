package com.jordansimsmith.tcginventory.pricing.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetCardRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceRequest;
import com.jordansimsmith.tcginventory.pricing.client.GetPriceResponse;
import java.math.RoundingMode;

public class GetPriceService {
  private final PricingRepository pricingRepository;
  private final FetchTcgService fetchTcgService;
  private final CompetitorPricing competitorPricing;
  private final PricingPolicy pricingPolicy;

  public GetPriceService(PricingRepository pricingRepository, FetchTcgService fetchTcgService) {
    this.pricingRepository = pricingRepository;
    this.fetchTcgService = fetchTcgService;
    this.competitorPricing = new CompetitorPricing(fetchTcgService);
    this.pricingPolicy = new PricingPolicy();
  }

  public GetPriceResponse getPrice(RequestContext ctx, GetPriceRequest request)
      throws ServiceException {
    if (request.fetchtcgCardId() == null || request.fetchtcgCardId().isBlank()) {
      throw new BadRequestException("fetchtcg_card_id is required");
    }
    if (request.condition() == null) {
      throw new BadRequestException("condition is required");
    }

    var pricingItem = pricingRepository.get(ctx.user());
    var priceFloor =
        PriceFloors.effective(pricingItem == null ? null : pricingItem.getPriceFloor());
    var cardId = request.fetchtcgCardId();
    var card = fetchTcgService.getCard(ctx, new GetCardRequest(cardId));
    var marketPrice = card.marketPrice();
    if (marketPrice == null) {
      return new GetPriceResponse(null, null);
    }
    marketPrice = marketPrice.setScale(2, RoundingMode.HALF_UP);
    var sellerUsername = fetchTcgService.getAccount(ctx, new GetAccountRequest()).sellerUsername();
    var competitors =
        competitorPricing.findCompetitorPriceTiers(
            ctx, cardId, request.condition(), sellerUsername);
    var suggestedPrice =
        pricingPolicy
            .calculatePrice(marketPrice, competitors, priceFloor)
            .setScale(2, RoundingMode.UNNECESSARY);
    return new GetPriceResponse(marketPrice, suggestedPrice);
  }
}
