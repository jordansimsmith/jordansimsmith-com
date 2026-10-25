package com.jordansimsmith.tcginventory.fetchtcg.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.GetAccountResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetAccountHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(GetAccountHandler.class);

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final FetchTcgService fetchTcgService;

  public GetAccountHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetAccountHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.fetchTcgService = factory.fetchTcgService();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (ServiceException e) {
      return httpResponseFactory.serviceError(e);
    } catch (Exception e) {
      LOGGER.error("error processing get FetchTCG account request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event)
      throws ServiceException {
    var requestContext = requestContextFactory.createCtx(event);
    GetAccountResponse response =
        fetchTcgService.getAccount(requestContext, new GetAccountRequest());
    return httpResponseFactory.ok(response);
  }
}
