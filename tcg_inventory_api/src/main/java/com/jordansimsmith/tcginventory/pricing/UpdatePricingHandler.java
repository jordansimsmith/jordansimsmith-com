package com.jordansimsmith.tcginventory.pricing;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.tcginventory.ActiveJob;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdatePricingHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(UpdatePricingHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  record PricingResponse(@JsonProperty("price_floor") String priceFloor) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record UpdatePricingRequest(@JsonProperty("price_floor") BigDecimal priceFloor) {}

  private final ObjectMapper objectMapper;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final ActiveJob activeJob;
  private final PricingRepository pricingRepository;

  public UpdatePricingHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  UpdatePricingHandler(TcgInventoryFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.activeJob = new ActiveJob(factory.jobTable());
    this.pricingRepository = new PricingRepository(factory);
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing update pricing request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();

    UpdatePricingRequest body;
    try {
      body = objectMapper.readValue(event.getBody(), UpdatePricingRequest.class);
    } catch (Exception e) {
      return httpResponseFactory.badRequest(new ErrorResponse("invalid request body"));
    }

    if (body == null) {
      return httpResponseFactory.badRequest(new ErrorResponse("request body must be an object"));
    }

    if (body.priceFloor() == null) {
      var item = pricingRepository.get(user);
      var effectivePriceFloor =
          item == null || item.getPriceFloor() == null
              ? Pricing.DEFAULT_PRICE_FLOOR
              : new BigDecimal(item.getPriceFloor()).setScale(2);
      return httpResponseFactory.ok(new PricingResponse(effectivePriceFloor.toPlainString()));
    }

    var requestedPriceFloor = body.priceFloor();
    if (requestedPriceFloor.signum() < 0 || requestedPriceFloor.scale() > 2) {
      return httpResponseFactory.badRequest(
          new ErrorResponse("price_floor must be a nonnegative decimal with at most two places"));
    }
    var effectivePriceFloor = requestedPriceFloor.setScale(2);

    if (activeJob.exists(user)) {
      return httpResponseFactory.conflict(new ErrorResponse("another job is in progress"));
    }

    pricingRepository.update(PricingItem.create(user, effectivePriceFloor.toPlainString()));
    return httpResponseFactory.ok(new PricingResponse(effectivePriceFloor.toPlainString()));
  }
}
