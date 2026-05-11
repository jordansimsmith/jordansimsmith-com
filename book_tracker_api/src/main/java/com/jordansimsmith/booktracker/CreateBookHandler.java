package com.jordansimsmith.booktracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.time.Clock;
import java.time.LocalDate;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

public class CreateBookHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(CreateBookHandler.class);

  @VisibleForTesting
  record CreateBookRequest(
      @JsonProperty("open_library_work_id") String openLibraryWorkId,
      @JsonProperty("title") String title,
      @Nullable @JsonProperty("authors") List<String> authors,
      @Nullable @JsonProperty("cover_url") String coverUrl,
      @Nullable @JsonProperty("page_count") Integer pageCount,
      @Nullable @JsonProperty("publication_year") Integer publicationYear,
      @JsonProperty("finished_date") String finishedDate) {}

  @VisibleForTesting
  record CreateBookResponse(@JsonProperty("book") Book book) {}

  @VisibleForTesting
  record ErrorResponse(@JsonProperty("message") String message) {}

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<BookTrackerItem> bookTrackerTable;
  private final BookValidator bookValidator;

  public CreateBookHandler() {
    this(BookTrackerFactory.create());
  }

  @VisibleForTesting
  CreateBookHandler(BookTrackerFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.bookTrackerTable = factory.bookTrackerTable();
    this.bookValidator = factory.bookValidator();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing create book request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) throws Exception {
    var user = requestContextFactory.createCtx(event).user();
    var request = objectMapper.readValue(event.getBody(), CreateBookRequest.class);

    var now = clock.now();
    var epochSeconds = now.getEpochSecond();

    var book =
        new Book(
            request.openLibraryWorkId(),
            request.title(),
            request.authors() != null ? request.authors() : List.of(),
            request.coverUrl(),
            request.pageCount(),
            request.publicationYear(),
            request.finishedDate(),
            epochSeconds,
            epochSeconds);

    try {
      bookValidator.validate(book);
    } catch (BookValidator.ValidationException e) {
      return httpResponseFactory.badRequest(new ErrorResponse(e.getMessage()));
    }

    var finishedDate = LocalDate.parse(book.finishedDate());

    var item =
        BookTrackerItem.create(
            user,
            book.openLibraryWorkId(),
            book.title(),
            book.authors(),
            book.coverUrl(),
            book.pageCount(),
            book.publicationYear(),
            finishedDate,
            now,
            now);

    try {
      bookTrackerTable.putItem(
          PutItemEnhancedRequest.builder(BookTrackerItem.class)
              .item(item)
              .conditionExpression(
                  Expression.builder()
                      .expression("attribute_not_exists(pk) AND attribute_not_exists(sk)")
                      .build())
              .build());
    } catch (ConditionalCheckFailedException e) {
      var existing =
          bookTrackerTable.getItem(
              Key.builder()
                  .partitionValue(BookTrackerItem.formatPk(user))
                  .sortValue(BookTrackerItem.formatSk(book.openLibraryWorkId()))
                  .build());
      return httpResponseFactory.conflict(
          new ErrorResponse("already added on " + existing.getFinishedDate()));
    }

    return httpResponseFactory.created(new CreateBookResponse(book));
  }
}
