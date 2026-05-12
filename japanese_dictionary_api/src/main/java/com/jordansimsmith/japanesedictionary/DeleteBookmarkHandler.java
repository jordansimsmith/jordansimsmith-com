package com.jordansimsmith.japanesedictionary;

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

public class DeleteBookmarkHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(DeleteBookmarkHandler.class);

  @VisibleForTesting
  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<BookmarkItem> bookmarkTable;

  public DeleteBookmarkHandler() {
    this(JapaneseDictionaryFactory.create());
  }

  @VisibleForTesting
  DeleteBookmarkHandler(JapaneseDictionaryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.bookmarkTable = factory.bookmarkTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing delete bookmark request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();

    var raw = event.getPathParameters() == null ? null : event.getPathParameters().get("sequence");
    long sequence;
    try {
      sequence = Long.parseLong(raw);
    } catch (NumberFormatException e) {
      return httpResponseFactory.badRequest(
          new ErrorResponse("sequence must be a positive integer"));
    }
    if (sequence <= 0) {
      return httpResponseFactory.badRequest(
          new ErrorResponse("sequence must be a positive integer"));
    }

    var key =
        Key.builder()
            .partitionValue(BookmarkItem.formatPk(user))
            .sortValue(BookmarkItem.formatSk(sequence))
            .build();
    bookmarkTable.deleteItem(key);

    return httpResponseFactory.noContent();
  }
}
