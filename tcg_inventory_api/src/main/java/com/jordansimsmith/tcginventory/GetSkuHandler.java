package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class GetSkuHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetSkuHandler.class);

  record UnitResponse(
      @JsonProperty("sequence_number") int sequenceNumber,
      @JsonProperty("location") String location,
      @JsonProperty("status") String status) {}

  record SkuDetailResponse(
      @JsonProperty("sku_id") String skuId,
      @JsonProperty("scryfall_id") String scryfallId,
      @JsonProperty("name") String name,
      @JsonProperty("set_code") String setCode,
      @JsonProperty("set_name") String setName,
      @JsonProperty("collector_number") String collectorNumber,
      @JsonProperty("finish") String finish,
      @JsonProperty("condition") String condition,
      @JsonProperty("last_published_price") @Nullable String lastPublishedPrice,
      @JsonProperty("in_stock_count") int inStockCount,
      @JsonProperty("reserved_count") int reservedCount,
      @JsonProperty("sold_count") int soldCount,
      @JsonProperty("units") List<UnitResponse> units) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

  public GetSkuHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetSkuHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing get sku request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var skuId = event.getPathParameters().get("sku_id");

    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);

    var queryConditional = QueryConditional.keyEqualTo(Key.builder().partitionValue(skuPk).build());
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(queryConditional)
            .scanIndexForward(true)
            .build();

    var items =
        tcgInventoryTable.query(request).stream().flatMap(page -> page.items().stream()).toList();

    var skuItem =
        items.stream()
            .filter(item -> TcgInventoryItem.formatSkuSk().equals(item.getSk()))
            .findFirst()
            .orElse(null);

    if (skuItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var unitItems =
        items.stream()
            .filter(
                item ->
                    item.getSk() != null && item.getSk().startsWith(TcgInventoryItem.UNIT_PREFIX))
            .toList();

    int inStockCount = 0;
    int reservedCount = 0;
    int soldCount = 0;
    for (var unit : unitItems) {
      switch (unit.getStatus()) {
        case "in_stock" -> inStockCount++;
        case "reserved" -> reservedCount++;
        case "sold" -> soldCount++;
      }
    }

    var units =
        unitItems.stream()
            .filter(unit -> !"removed".equals(unit.getStatus()))
            .map(
                unit ->
                    new UnitResponse(
                        unit.getSequenceNumber(),
                        InventoryLocation.formatLocation(unit.getSequenceNumber()),
                        unit.getStatus()))
            .toList();

    return httpResponseFactory.ok(
        new SkuDetailResponse(
            skuItem.getSkuId(),
            skuItem.getScryfallId(),
            skuItem.getName(),
            skuItem.getSetCode(),
            skuItem.getSetName(),
            skuItem.getCollectorNumber(),
            skuItem.getFinish(),
            skuItem.getCondition(),
            skuItem.getLastPublishedPrice(),
            inStockCount,
            reservedCount,
            soldCount,
            units));
  }
}
