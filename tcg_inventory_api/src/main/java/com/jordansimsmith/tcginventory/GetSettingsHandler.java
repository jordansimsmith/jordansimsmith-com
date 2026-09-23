package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class GetSettingsHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetSettingsHandler.class);

  record GetSettingsResponse(
      @JsonProperty("credential_set") boolean credentialSet,
      @JsonProperty("updated_at") @Nullable Long updatedAt,
      @JsonProperty("track_orders_after") @Nullable Long trackOrdersAfter) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<SettingsItem> settingsTable;

  public GetSettingsHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetSettingsHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.settingsTable = factory.settingsTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing get settings request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();

    var key =
        Key.builder()
            .partitionValue(SettingsItem.formatPk(user))
            .sortValue(SettingsItem.formatSk())
            .build();

    var settingsItem = settingsTable.getItem(key);

    if (settingsItem == null) {
      return httpResponseFactory.ok(new GetSettingsResponse(false, null, null));
    }

    var credentialSet = settingsItem.getUpdatedAt() != null;
    var updatedAt = credentialSet ? settingsItem.getUpdatedAt().getEpochSecond() : null;
    var trackOrdersAfter =
        settingsItem.getTrackOrdersAfter() != null
            ? settingsItem.getTrackOrdersAfter().getEpochSecond()
            : null;

    return httpResponseFactory.ok(
        new GetSettingsResponse(credentialSet, updatedAt, trackOrdersAfter));
  }
}
