package com.jordansimsmith.booktracker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

public class UpdateBookHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateBookHandler.class);

  @VisibleForTesting
  @JsonIgnoreProperties(ignoreUnknown = true)
  record UpdateBookRequest(@JsonProperty("finished_date") String finishedDate) {}

  @VisibleForTesting
  record UpdateBookResponse(@JsonProperty("book") Book book) {}

  @VisibleForTesting
  record ErrorResponse(@JsonProperty("message") String message) {}

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<BookTrackerItem> bookTrackerTable;
  private final BookValidator bookValidator;

  public UpdateBookHandler() {
    this(BookTrackerFactory.create());
  }

  @VisibleForTesting
  UpdateBookHandler(BookTrackerFactory factory) {
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
      LOGGER.error("error processing update book request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) throws Exception {
    var user = requestContextFactory.createCtx(event).user();
    var openLibraryWorkId = event.getPathParameters().get("open_library_work_id");
    var request = objectMapper.readValue(event.getBody(), UpdateBookRequest.class);

    try {
      bookValidator.validateFinishedDate(request.finishedDate());
    } catch (BookValidator.ValidationException e) {
      return httpResponseFactory.badRequest(new ErrorResponse(e.getMessage()));
    }

    var key =
        Key.builder()
            .partitionValue(BookTrackerItem.formatPk(user))
            .sortValue(BookTrackerItem.formatSk(openLibraryWorkId))
            .build();

    var existing = bookTrackerTable.getItem(key);
    if (existing == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var newFinishedDate = LocalDate.parse(request.finishedDate());
    var now = clock.now();

    existing.setFinishedDate(newFinishedDate);
    existing.setGsi1sk(BookTrackerItem.formatGsi1sk(newFinishedDate, openLibraryWorkId));
    existing.setUpdatedAt(now);

    bookTrackerTable.putItem(existing);

    var book =
        new Book(
            existing.getOpenLibraryWorkId(),
            existing.getTitle(),
            existing.getAuthors(),
            existing.getCoverUrl(),
            existing.getPageCount(),
            existing.getPublicationYear(),
            existing.getFinishedDate().toString(),
            existing.getCreatedAt().getEpochSecond(),
            existing.getUpdatedAt().getEpochSecond());

    return httpResponseFactory.ok(new UpdateBookResponse(book));
  }
}
