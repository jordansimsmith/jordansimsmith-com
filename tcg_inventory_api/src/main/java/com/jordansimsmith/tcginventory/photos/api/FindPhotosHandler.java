package com.jordansimsmith.tcginventory.photos.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.TcgInventoryFactory;
import com.jordansimsmith.tcginventory.photos.client.FindPhotosRequest;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FindPhotosHandler
    implements RequestHandler<FindPhotosHandler.HttpEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(FindPhotosHandler.class);

  public static class HttpEvent extends APIGatewayV2HTTPEvent {
    private Map<String, List<String>> multiValueQueryStringParameters;

    public Map<String, List<String>> getMultiValueQueryStringParameters() {
      return multiValueQueryStringParameters;
    }

    public void setMultiValueQueryStringParameters(
        Map<String, List<String>> multiValueQueryStringParameters) {
      this.multiValueQueryStringParameters = multiValueQueryStringParameters;
    }
  }

  private final PhotosService photosService;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;

  public FindPhotosHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  FindPhotosHandler(TcgInventoryFactory factory) {
    this.photosService = factory.photosService();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(HttpEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (ServiceException e) {
      return httpResponseFactory.serviceError(e);
    } catch (Exception e) {
      LOGGER.error("error processing find photos request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(HttpEvent event) throws ServiceException {
    Map<String, List<String>> queryParams = event.getMultiValueQueryStringParameters();
    var photoIds = queryParams != null ? queryParams.get("photo_ids") : null;
    var ctx = requestContextFactory.createCtx(event);
    return httpResponseFactory.ok(photosService.findPhotos(ctx, new FindPhotosRequest(photoIds)));
  }
}
