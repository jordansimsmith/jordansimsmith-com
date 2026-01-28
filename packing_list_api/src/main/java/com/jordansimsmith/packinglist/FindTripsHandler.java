package com.jordansimsmith.packinglist;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.RequestContextFactory;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class FindTripsHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(FindTripsHandler.class);

  @VisibleForTesting
  record FindTripsResponse(@JsonProperty("trips") List<TripSummary> trips) {}

  private final ObjectMapper objectMapper;
  private final RequestContextFactory requestContextFactory;
  private final DynamoDbTable<PackingListItem> packingListTable;

  public FindTripsHandler() {
    this(PackingListFactory.create());
  }

  @VisibleForTesting
  FindTripsHandler(PackingListFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.requestContextFactory = factory.requestContextFactory();
    this.packingListTable = factory.packingListTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("error processing find trips request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var user = requestContextFactory.createCtx(event).user();

    DynamoDbIndex<PackingListItem> gsi1Index = packingListTable.index(PackingListItem.GSI1_NAME);

    var queryRequest =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(
                    k -> k.partitionValue(PackingListItem.formatGsi1pk(user))))
            .scanIndexForward(false)
            .build();

    var trips =
        gsi1Index.query(queryRequest).stream()
            .flatMap(page -> page.items().stream())
            .map(
                item ->
                    new TripSummary(
                        item.getTripId(),
                        item.getName(),
                        item.getDestination(),
                        item.getDepartureDate().toString(),
                        item.getReturnDate().toString(),
                        item.getCreatedAt().getEpochSecond(),
                        item.getUpdatedAt().getEpochSecond()))
            .toList();

    var response = new FindTripsResponse(trips);

    return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(200)
        .withHeaders(
            Map.of(
                "Content-Type",
                "application/json; charset=utf-8",
                "Access-Control-Allow-Origin",
                "https://packing-list.jordansimsmith.com"))
        .withBody(objectMapper.writeValueAsString(response))
        .build();
  }
}
