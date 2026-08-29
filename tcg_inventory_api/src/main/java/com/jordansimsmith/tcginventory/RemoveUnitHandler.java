package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class RemoveUnitHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(RemoveUnitHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final TcgInventoryItemRepository tcgInventoryItemRepository;

  public RemoveUnitHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  RemoveUnitHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
    this.tcgInventoryItemRepository = factory.tcgInventoryItemRepository();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing remove unit request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    // api gateway rest proxy integrations pass path parameters still url-encoded
    var skuId = URLDecoder.decode(event.getPathParameters().get("sku_id"), StandardCharsets.UTF_8);
    var sequenceNumber = Integer.parseInt(event.getPathParameters().get("sequence_number"));
    var queryParams = event.getQueryStringParameters();
    var reason = queryParams != null ? queryParams.get("reason") : null;

    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var unitSk = TcgInventoryItem.formatUnitSk(sequenceNumber);

    var unitItem =
        tcgInventoryTable.getItem(Key.builder().partitionValue(skuPk).sortValue(unitSk).build());
    if (unitItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    if (!"in_stock".equals(unitItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("unit is not in stock"));
    }

    tcgInventoryItemRepository.removeUnit(user, skuId, sequenceNumber, reason);

    return httpResponseFactory.noContent();
  }
}
