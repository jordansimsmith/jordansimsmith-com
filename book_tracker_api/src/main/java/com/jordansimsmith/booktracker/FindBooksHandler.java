package com.jordansimsmith.booktracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class FindBooksHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(FindBooksHandler.class);

  private static final int ROLLING_WINDOW_DAYS = 365;

  @VisibleForTesting
  record FindBooksResponse(
      @JsonProperty("books") List<Book> books,
      @JsonProperty("rolling_12_month_count") long rollingTwelveMonthCount) {}

  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<BookTrackerItem> bookTrackerTable;

  public FindBooksHandler() {
    this(BookTrackerFactory.create());
  }

  @VisibleForTesting
  FindBooksHandler(BookTrackerFactory factory) {
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.bookTrackerTable = factory.bookTrackerTable();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("error processing find books request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context) {
    var user = requestContextFactory.createCtx(event).user();

    DynamoDbIndex<BookTrackerItem> gsi1Index = bookTrackerTable.index(BookTrackerItem.GSI1_NAME);

    var queryRequest =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(
                    k -> k.partitionValue(BookTrackerItem.formatGsi1pk(user))))
            .scanIndexForward(false)
            .build();

    var items =
        gsi1Index.query(queryRequest).stream().flatMap(page -> page.items().stream()).toList();

    var today = LocalDate.ofInstant(clock.now(), ZoneOffset.UTC);
    var cutoff = today.minusDays(ROLLING_WINDOW_DAYS);
    var rollingCount =
        items.stream()
            .filter(
                item ->
                    !item.getFinishedDate().isBefore(cutoff)
                        && !item.getFinishedDate().isAfter(today))
            .count();

    var books = items.stream().map(this::toBook).toList();

    return httpResponseFactory.ok(new FindBooksResponse(books, rollingCount));
  }

  private Book toBook(BookTrackerItem item) {
    return new Book(
        item.getOpenLibraryWorkId(),
        item.getTitle(),
        item.getAuthors(),
        item.getCoverUrl(),
        item.getPageCount(),
        item.getPublicationYear(),
        item.getFinishedDate().toString(),
        item.getCreatedAt().getEpochSecond(),
        item.getUpdatedAt().getEpochSecond());
  }
}
