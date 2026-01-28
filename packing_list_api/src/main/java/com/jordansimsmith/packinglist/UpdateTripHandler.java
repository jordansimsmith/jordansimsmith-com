package com.jordansimsmith.packinglist;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class UpdateTripHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateTripHandler.class);

  @VisibleForTesting
  record UpdateTripRequest(
      @JsonProperty("trip_id") String tripId,
      @JsonProperty("name") String name,
      @JsonProperty("destination") String destination,
      @JsonProperty("departure_date") String departureDate,
      @JsonProperty("return_date") String returnDate,
      @JsonProperty("items") List<Trip.Item> items) {}

  @VisibleForTesting
  record UpdateTripResponse(@JsonProperty("trip") Trip trip) {}

  @VisibleForTesting
  record ErrorResponse(@JsonProperty("message") String message) {}

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final DynamoDbTable<PackingListItem> packingListTable;
  private final TripValidator tripValidator;

  public UpdateTripHandler() {
    this(PackingListFactory.create());
  }

  @VisibleForTesting
  UpdateTripHandler(PackingListFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.packingListTable = factory.packingListTable();
    this.tripValidator = factory.tripValidator();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("error processing update trip request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var user = requestContextFactory.createCtx(event).user();
    var pathTripId = event.getPathParameters().get("trip_id");
    var request = objectMapper.readValue(event.getBody(), UpdateTripRequest.class);

    if (!pathTripId.equals(request.tripId())) {
      return APIGatewayV2HTTPResponse.builder()
          .withStatusCode(400)
          .withHeaders(
              Map.of(
                  "Content-Type",
                  "application/json; charset=utf-8",
                  "Access-Control-Allow-Origin",
                  "https://packing-list.jordansimsmith.com"))
          .withBody(objectMapper.writeValueAsString(new ErrorResponse("trip_id mismatch")))
          .build();
    }

    var key =
        Key.builder()
            .partitionValue(PackingListItem.formatPk(user))
            .sortValue(PackingListItem.formatSk(pathTripId))
            .build();

    var existingItem = packingListTable.getItem(key);

    if (existingItem == null) {
      return APIGatewayV2HTTPResponse.builder()
          .withStatusCode(404)
          .withHeaders(
              Map.of(
                  "Content-Type",
                  "application/json; charset=utf-8",
                  "Access-Control-Allow-Origin",
                  "https://packing-list.jordansimsmith.com"))
          .withBody(objectMapper.writeValueAsString(new ErrorResponse("Not Found")))
          .build();
    }

    var now = clock.now();
    var epochSeconds = now.getEpochSecond();

    var trip =
        new Trip(
            pathTripId,
            request.name(),
            request.destination(),
            request.departureDate(),
            request.returnDate(),
            request.items() != null ? request.items() : List.of(),
            existingItem.getCreatedAt().getEpochSecond(),
            epochSeconds);

    try {
      tripValidator.validate(trip);
    } catch (TripValidator.ValidationException e) {
      return APIGatewayV2HTTPResponse.builder()
          .withStatusCode(400)
          .withHeaders(
              Map.of(
                  "Content-Type",
                  "application/json; charset=utf-8",
                  "Access-Control-Allow-Origin",
                  "https://packing-list.jordansimsmith.com"))
          .withBody(objectMapper.writeValueAsString(new ErrorResponse(e.getMessage())))
          .build();
    }

    var departureDate = LocalDate.parse(trip.departureDate());
    var returnDate = LocalDate.parse(trip.returnDate());

    var tripItems =
        trip.items().stream()
            .map(
                item ->
                    TripItem.create(
                        item.name(),
                        item.category(),
                        item.quantity(),
                        item.tags() != null ? item.tags() : List.of(),
                        TripItemStatus.fromValue(item.status())))
            .toList();

    var packingListItem =
        PackingListItem.create(
            user,
            pathTripId,
            trip.name(),
            trip.destination(),
            departureDate,
            returnDate,
            tripItems,
            existingItem.getCreatedAt(),
            now);

    packingListTable.putItem(packingListItem);

    var response = new UpdateTripResponse(trip);

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
