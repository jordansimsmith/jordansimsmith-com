package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.dynamodb.Continuations;
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

public class FindSkusHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(FindSkusHandler.class);
  private static final int DEFAULT_LIMIT = 20;

  record SkuSummaryResponse(
      @JsonProperty("sku_id") String skuId,
      @JsonProperty("name") String name,
      @JsonProperty("set_code") String setCode,
      @JsonProperty("set_name") String setName,
      @JsonProperty("collector_number") String collectorNumber,
      @JsonProperty("finish") String finish,
      @JsonProperty("condition") String condition) {}

  record FindSkusResponse(
      @JsonProperty("skus") List<SkuSummaryResponse> skus,
      @JsonProperty("next_continuation") @Nullable String nextContinuation) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final ObjectMapper objectMapper;

  public FindSkusHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  FindSkusHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
    this.objectMapper = factory.objectMapper();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing find skus request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();

    var queryParams = event.getQueryStringParameters();
    var search = queryParams != null ? queryParams.get("search") : null;
    var continuation = queryParams != null ? queryParams.get("continuation") : null;
    var limitParam = queryParams != null ? queryParams.get("limit") : null;
    int limit = limitParam != null ? Integer.parseInt(limitParam) : DEFAULT_LIMIT;

    var gsi2pk = TcgInventoryItem.formatGsi2pk(user);
    var sortPrefix =
        search != null && !search.isEmpty()
            ? TcgInventoryItem.NAME_PREFIX + search.toLowerCase()
            : TcgInventoryItem.NAME_PREFIX;

    var queryConditional =
        QueryConditional.sortBeginsWith(
            Key.builder().partitionValue(gsi2pk).sortValue(sortPrefix).build());

    var requestBuilder =
        QueryEnhancedRequest.builder()
            .queryConditional(queryConditional)
            .scanIndexForward(true)
            .limit(limit);

    if (continuation != null && !continuation.isEmpty()) {
      var exclusiveStartKey = Continuations.decode(continuation, objectMapper);
      if (exclusiveStartKey != null) {
        requestBuilder.exclusiveStartKey(exclusiveStartKey);
      }
    }

    var gsi2Index = tcgInventoryTable.index(TcgInventoryItem.GSI2_NAME);
    var page = gsi2Index.query(requestBuilder.build()).stream().findFirst().orElse(null);

    if (page == null) {
      return httpResponseFactory.ok(new FindSkusResponse(List.of(), null));
    }

    var skus =
        page.items().stream()
            .map(
                item ->
                    new SkuSummaryResponse(
                        item.getSkuId(),
                        item.getName(),
                        item.getSetCode(),
                        item.getSetName(),
                        item.getCollectorNumber(),
                        item.getFinish(),
                        item.getCondition()))
            .toList();

    var lastEvaluatedKey = page.lastEvaluatedKey();
    String nextContinuation = null;
    if (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty()) {
      nextContinuation = Continuations.encode(lastEvaluatedKey, objectMapper);
    }

    return httpResponseFactory.ok(new FindSkusResponse(skus, nextContinuation));
  }
}
