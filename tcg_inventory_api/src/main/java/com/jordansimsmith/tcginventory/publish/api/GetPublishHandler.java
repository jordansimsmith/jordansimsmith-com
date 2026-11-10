package com.jordansimsmith.tcginventory.publish.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.publish.client.GetPublishRequest;
import com.jordansimsmith.tcginventory.publish.client.PublishService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetPublishHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetPublishHandler.class);

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final PublishService publishService;

  public GetPublishHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetPublishHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.publishService = factory.publishService();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (ServiceException e) {
      return httpResponseFactory.serviceError(e);
    } catch (Exception e) {
      LOGGER.error("error processing get publish request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var ctx = requestContextFactory.createCtx(event);
    return httpResponseFactory.ok(publishService.getPublish(ctx, new GetPublishRequest()));
  }
}
