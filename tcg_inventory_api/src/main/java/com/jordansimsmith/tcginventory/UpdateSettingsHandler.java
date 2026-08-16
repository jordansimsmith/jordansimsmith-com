package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.secrets.Secrets;
import com.jordansimsmith.time.Clock;
import java.time.Instant;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class UpdateSettingsHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateSettingsHandler.class);
  private static final String SECRET_NAME = "tcg_inventory";

  record ErrorResponse(@JsonProperty("message") String message) {}

  record UpdateSettingsRequest(
      @JsonProperty("refresh_token") @Nullable String refreshToken,
      @JsonProperty("track_orders_after") @Nullable Long trackOrdersAfter) {}

  record UpdateSettingsResponse(
      @JsonProperty("credential_set") boolean credentialSet,
      @JsonProperty("updated_at") @Nullable Long updatedAt,
      @JsonProperty("track_orders_after") @Nullable Long trackOrdersAfter) {}

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final Secrets secrets;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

  public UpdateSettingsHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  UpdateSettingsHandler(TcgInventoryFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.secrets = factory.secrets();
    this.tcgInventoryTable = factory.tcgInventoryTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing update settings request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) throws Exception {
    var user = requestContextFactory.createCtx(event).user();
    var request = objectMapper.readValue(event.getBody(), UpdateSettingsRequest.class);

    var hasRefreshToken = request.refreshToken() != null && !request.refreshToken().isBlank();
    var hasTrackOrdersAfter = request.trackOrdersAfter() != null;

    if (!hasRefreshToken && !hasTrackOrdersAfter) {
      return httpResponseFactory.badRequest(
          new ErrorResponse("at least one of refresh_token or track_orders_after is required"));
    }

    var key =
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatSettingsSk())
            .build();

    var settingsItem = tcgInventoryTable.getItem(key);
    if (settingsItem == null) {
      settingsItem = new TcgInventoryItem();
      settingsItem.setPk(TcgInventoryItem.formatUserPk(user));
      settingsItem.setSk(TcgInventoryItem.formatSettingsSk());
    }

    if (hasRefreshToken) {
      var currentSecret = secrets.get(SECRET_NAME);
      var secretNode = (ObjectNode) objectMapper.readTree(currentSecret);
      secretNode.put(user, request.refreshToken());
      secrets.put(SECRET_NAME, objectMapper.writeValueAsString(secretNode));
      settingsItem.setUpdatedAt(clock.now());
    }

    if (hasTrackOrdersAfter) {
      settingsItem.setTrackOrdersAfter(Instant.ofEpochSecond(request.trackOrdersAfter()));
    }

    tcgInventoryTable.putItem(settingsItem);

    var credentialSet = settingsItem.getUpdatedAt() != null;
    var updatedAt = credentialSet ? settingsItem.getUpdatedAt().getEpochSecond() : null;
    var trackOrdersAfter =
        settingsItem.getTrackOrdersAfter() != null
            ? settingsItem.getTrackOrdersAfter().getEpochSecond()
            : null;

    return httpResponseFactory.ok(
        new UpdateSettingsResponse(credentialSet, updatedAt, trackOrdersAfter));
  }
}
