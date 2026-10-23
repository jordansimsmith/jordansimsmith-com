package com.jordansimsmith.tcginventory.pricing;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetPricingHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(GetPricingHandler.class);

  record PricingResponse(@JsonProperty("price_floor") String priceFloor) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final PricingRepository pricingRepository;

  public GetPricingHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetPricingHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.pricingRepository = new PricingRepository(factory);
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing get pricing request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var item = pricingRepository.get(user);
    var priceFloor =
        item == null || item.getPriceFloor() == null
            ? Pricing.DEFAULT_PRICE_FLOOR
            : new BigDecimal(item.getPriceFloor()).setScale(2);
    return httpResponseFactory.ok(new PricingResponse(priceFloor.toPlainString()));
  }
}
