package com.jordansimsmith.packinglist;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

public class CreateTripHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(CreateTripHandler.class);

  @VisibleForTesting
  record CreateTripRequest(
      @JsonProperty("name") String name,
      @JsonProperty("destination") String destination,
      @JsonProperty("departure_date") String departureDate,
      @JsonProperty("return_date") String returnDate,
      @JsonProperty("items") List<Trip.Item> items) {}

  @VisibleForTesting
  record CreateTripResponse(@JsonProperty("trip") Trip trip) {}

  @VisibleForTesting
  record ErrorResponse(@JsonProperty("message") String message) {}

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final DynamoDbTable<PackingListItem> packingListTable;
  private final TripValidator tripValidator;

  public CreateTripHandler() {
    this(PackingListFactory.create());
  }

  @VisibleForTesting
  CreateTripHandler(PackingListFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.clock = factory.clock();
    this.packingListTable = factory.packingListTable();
    this.tripValidator = factory.tripValidator();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("error processing create trip request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var user = event.getQueryStringParameters().get("user");
    var request = objectMapper.readValue(event.getBody(), CreateTripRequest.class);

    var tripId = UUID.randomUUID().toString();
    var now = clock.now();
    var epochSeconds = now.getEpochSecond();

    var trip =
        new Trip(
            tripId,
            request.name(),
            request.destination(),
            request.departureDate(),
            request.returnDate(),
            request.items() != null ? request.items() : List.of(),
            epochSeconds,
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
            tripId,
            trip.name(),
            trip.destination(),
            departureDate,
            returnDate,
            tripItems,
            now,
            now);

    packingListTable.putItem(packingListItem);

    var response = new CreateTripResponse(trip);

    return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(201)
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
