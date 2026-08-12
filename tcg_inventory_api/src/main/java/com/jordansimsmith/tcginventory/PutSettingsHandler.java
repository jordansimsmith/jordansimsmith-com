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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

public class PutSettingsHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(PutSettingsHandler.class);
  private static final String SECRET_NAME = "tcg_inventory";

  record PutSettingsRequest(@JsonProperty("refresh_token") String refreshToken) {}

  record PutSettingsResponse(@JsonProperty("message") String message) {}

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final Secrets secrets;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

  public PutSettingsHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  PutSettingsHandler(TcgInventoryFactory factory) {
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
      LOGGER.error("error processing put settings request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) throws Exception {
    var user = requestContextFactory.createCtx(event).user();
    var request = objectMapper.readValue(event.getBody(), PutSettingsRequest.class);

    if (request.refreshToken() == null || request.refreshToken().isBlank()) {
      return httpResponseFactory.badRequest(new PutSettingsResponse("refresh_token is required"));
    }

    var now = clock.now();

    var currentSecret = secrets.get(SECRET_NAME);
    var secretNode = (ObjectNode) objectMapper.readTree(currentSecret);
    secretNode.put(user, request.refreshToken());
    secrets.put(SECRET_NAME, objectMapper.writeValueAsString(secretNode));

    var settingsItem = new TcgInventoryItem();
    settingsItem.setPk(TcgInventoryItem.formatUserPk(user));
    settingsItem.setSk(TcgInventoryItem.formatSettingsSk());
    settingsItem.setUpdatedAt(now);
    tcgInventoryTable.putItem(settingsItem);

    return httpResponseFactory.ok(new PutSettingsResponse("settings updated"));
  }
}
