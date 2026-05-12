package com.jordansimsmith.japanesedictionary;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class FindBookmarksHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(FindBookmarksHandler.class);

  @VisibleForTesting
  record FindBookmarksResponse(@JsonProperty("sequences") List<Long> sequences) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<BookmarkItem> bookmarkTable;

  public FindBookmarksHandler() {
    this(JapaneseDictionaryFactory.create());
  }

  @VisibleForTesting
  FindBookmarksHandler(JapaneseDictionaryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.bookmarkTable = factory.bookmarkTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing find bookmarks request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();

    var queryRequest =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(BookmarkItem.formatPk(user))
                        .sortValue(BookmarkItem.BOOKMARK_PREFIX)
                        .build()))
            .build();

    var sequences =
        bookmarkTable.query(queryRequest).stream()
            .flatMap(page -> page.items().stream())
            .sorted(
                Comparator.comparing(BookmarkItem::getCreatedAt)
                    .reversed()
                    .thenComparing(BookmarkItem::getSequence))
            .map(BookmarkItem::getSequence)
            .toList();

    return httpResponseFactory.ok(new FindBookmarksResponse(sequences));
  }
}
