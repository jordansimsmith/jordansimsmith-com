package com.jordansimsmith.immersiontracker;

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

public class GetShowsHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetShowsHandler.class);

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<ImmersionTrackerItem> immersionTrackerTable;

  @VisibleForTesting
  record GetShowsResponse(@JsonProperty("shows") List<Show> shows) {}

  @VisibleForTesting
  record Show(
      @JsonProperty("folder_name") String folderName,
      @Nullable @JsonProperty("tvdb_id") Integer tvdbId,
      @Nullable @JsonProperty("tvdb_name") String tvdbName,
      @Nullable @JsonProperty("tvdb_image") String tvdbImage) {}

  public GetShowsHandler() {
    this(ImmersionTrackerFactory.create());
  }

  @VisibleForTesting
  GetShowsHandler(ImmersionTrackerFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.immersionTrackerTable = factory.immersionTrackerTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("Error processing get shows request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var user = requestContextFactory.createCtx(event).user();

    var query =
        immersionTrackerTable.query(
            QueryEnhancedRequest.builder()
                .queryConditional(
                    QueryConditional.sortBeginsWith(
                        Key.builder()
                            .partitionValue(ImmersionTrackerItem.formatPk(user))
                            .sortValue(ImmersionTrackerItem.SHOW_PREFIX)
                            .build()))
                .build());
    var items = query.items().stream().toList();
    var shows =
        items.stream()
            .map(i -> new Show(i.getFolderName(), i.getTvdbId(), i.getTvdbName(), i.getTvdbImage()))
            .toList();

    var res = new GetShowsResponse(shows);

    return httpResponseFactory.ok(res);
  }
}
