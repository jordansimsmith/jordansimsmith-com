package com.jordansimsmith.immersiontracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class UpdateShowHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateShowHandler.class);

  private final ObjectMapper objectMapper;
  private final DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;
  private final TvdbClient tvdbClient;

  @VisibleForTesting
  record UpdateShowRequest(
      @JsonProperty("folder_name") String folderName, @JsonProperty("tvdb_id") int tvdbId) {}

  @VisibleForTesting
  record UpdateShowResponse() {}

  public UpdateShowHandler() {
    this(ImmersionTrackerFactory.create());
  }

  @VisibleForTesting
  UpdateShowHandler(ImmersionTrackerFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.immersionTrackerTable = factory.immersionTrackerTable();
    this.tvdbClient = factory.tvdbClient();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("Error processing update show request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var user = event.getQueryStringParameters().get("user");
    Preconditions.checkNotNull(user);

    var body = objectMapper.readValue(event.getBody(), UpdateShowRequest.class);

    var show =
        immersionTrackerTable.getItem(
            Key.builder()
                .partitionValue(ImmersionTrackerItem.formatPk(user))
                .sortValue(ImmersionTrackerItem.formatShowSk(body.folderName))
                .build());
    Preconditions.checkNotNull(show);

    var tvdbShow = tvdbClient.getShow(body.tvdbId);

    show.setTvdbId(tvdbShow.id());
    show.setTvdbName(tvdbShow.name());
    show.setTvdbImage(tvdbShow.image());
    immersionTrackerTable.updateItem(show);

    var res = new UpdateShowResponse();
    return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(200)
        .withHeaders(Map.of("Content-Type", "application/json; charset=utf-8"))
        .withBody(objectMapper.writeValueAsString(res))
        .build();
  }
}
