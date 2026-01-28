package com.jordansimsmith.packinglist;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class GetTripHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetTripHandler.class);

  @VisibleForTesting
  record GetTripResponse(@JsonProperty("trip") Trip trip) {}

  @VisibleForTesting
  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<PackingListItem> packingListTable;

  public GetTripHandler() {
    this(PackingListFactory.create());
  }

  @VisibleForTesting
  GetTripHandler(PackingListFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.packingListTable = factory.packingListTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("error processing get trip request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var user = requestContextFactory.createCtx(event).user();
    var tripId = event.getPathParameters().get("trip_id");

    var key =
        Key.builder()
            .partitionValue(PackingListItem.formatPk(user))
            .sortValue(PackingListItem.formatSk(tripId))
            .build();

    var packingListItem = packingListTable.getItem(key);

    if (packingListItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var tripItems =
        packingListItem.getItems().stream()
            .map(
                item ->
                    new Trip.Item(
                        item.getName(),
                        item.getCategory(),
                        item.getQuantity(),
                        item.getTags(),
                        item.getStatus().getValue()))
            .toList();

    var trip =
        new Trip(
            packingListItem.getTripId(),
            packingListItem.getName(),
            packingListItem.getDestination(),
            packingListItem.getDepartureDate().toString(),
            packingListItem.getReturnDate().toString(),
            tripItems,
            packingListItem.getCreatedAt().getEpochSecond(),
            packingListItem.getUpdatedAt().getEpochSecond());

    var response = new GetTripResponse(trip);

    return httpResponseFactory.ok(response);
  }
}
