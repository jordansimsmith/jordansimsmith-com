package com.jordansimsmith.booktracker;

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

public class GetBookHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetBookHandler.class);

  @VisibleForTesting
  record GetBookResponse(@JsonProperty("book") Book book) {}

  @VisibleForTesting
  record ErrorResponse(@JsonProperty("message") String message) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<BookTrackerItem> bookTrackerTable;

  public GetBookHandler() {
    this(BookTrackerFactory.create());
  }

  @VisibleForTesting
  GetBookHandler(BookTrackerFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.bookTrackerTable = factory.bookTrackerTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("error processing get book request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context) {
    var user = requestContextFactory.createCtx(event).user();
    var openLibraryWorkId = event.getPathParameters().get("open_library_work_id");

    var key =
        Key.builder()
            .partitionValue(BookTrackerItem.formatPk(user))
            .sortValue(BookTrackerItem.formatSk(openLibraryWorkId))
            .build();

    var item = bookTrackerTable.getItem(key);

    if (item == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var book =
        new Book(
            item.getOpenLibraryWorkId(),
            item.getTitle(),
            item.getAuthors(),
            item.getCoverUrl(),
            item.getPageCount(),
            item.getPublicationYear(),
            item.getFinishedDate().toString(),
            item.getCreatedAt().getEpochSecond(),
            item.getUpdatedAt().getEpochSecond());

    return httpResponseFactory.ok(new GetBookResponse(book));
  }
}
