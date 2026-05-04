package com.jordansimsmith.booktracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class FindBooksHandlerIntegrationTest {
  private static final Instant TODAY = Instant.parse("2026-05-04T12:00:00Z");
  private static final LocalDate TODAY_DATE = LocalDate.of(2026, 5, 4);

  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<BookTrackerItem> bookTrackerTable;

  private FindBooksHandler findBooksHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = BookTrackerTestFactory.create(dynamoDbContainer.getEndpoint());
    var table = factory.bookTrackerTable();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory = BookTrackerTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeClock.setTime(TODAY);
    objectMapper = factory.objectMapper();
    bookTrackerTable = factory.bookTrackerTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    findBooksHandler = new FindBooksHandler(factory);
  }

  private APIGatewayV2HTTPEvent buildEvent(String user) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder().withHeaders(Map.of("Authorization", authHeader)).build();
  }

  private BookTrackerItem buildBook(String user, String workId, LocalDate finishedDate) {
    return BookTrackerItem.create(
        user,
        workId,
        "Title for " + workId,
        List.of("Author for " + workId),
        "https://covers.openlibrary.org/b/id/" + workId + "-L.jpg",
        100,
        2000,
        finishedDate,
        TODAY,
        TODAY);
  }

  @Test
  void handleRequestShouldReturnEmptyListWhenNoBooks() throws Exception {
    // act
    var response = findBooksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8");

    var responseBody =
        objectMapper.readValue(response.getBody(), FindBooksHandler.FindBooksResponse.class);
    assertThat(responseBody.books()).isEmpty();
    assertThat(responseBody.rollingTwelveMonthCount()).isZero();
  }

  @Test
  void handleRequestShouldReturnSingleBookWithRollingCountOfOne() throws Exception {
    // arrange
    bookTrackerTable.putItem(buildBook("alice", "OL27448W", LocalDate.of(2026, 4, 1)));

    // act
    var response = findBooksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);

    var responseBody =
        objectMapper.readValue(response.getBody(), FindBooksHandler.FindBooksResponse.class);
    assertThat(responseBody.books()).hasSize(1);
    assertThat(responseBody.books().get(0).openLibraryWorkId()).isEqualTo("OL27448W");
    assertThat(responseBody.books().get(0).finishedDate()).isEqualTo("2026-04-01");
    assertThat(responseBody.books().get(0).coverUrl())
        .isEqualTo("https://covers.openlibrary.org/b/id/OL27448W-L.jpg");
    assertThat(responseBody.rollingTwelveMonthCount()).isEqualTo(1);
  }

  @Test
  void handleRequestShouldReturnBooksOrderedByFinishedDateDescending() throws Exception {
    // arrange
    bookTrackerTable.putItem(buildBook("alice", "OL1W", LocalDate.of(2026, 1, 12)));
    bookTrackerTable.putItem(buildBook("alice", "OL2W", LocalDate.of(2026, 4, 20)));
    bookTrackerTable.putItem(buildBook("alice", "OL3W", LocalDate.of(2025, 11, 2)));

    // act
    var response = findBooksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);

    var responseBody =
        objectMapper.readValue(response.getBody(), FindBooksHandler.FindBooksResponse.class);
    assertThat(responseBody.books()).hasSize(3);
    assertThat(responseBody.books().get(0).openLibraryWorkId()).isEqualTo("OL2W");
    assertThat(responseBody.books().get(0).finishedDate()).isEqualTo("2026-04-20");
    assertThat(responseBody.books().get(1).openLibraryWorkId()).isEqualTo("OL1W");
    assertThat(responseBody.books().get(1).finishedDate()).isEqualTo("2026-01-12");
    assertThat(responseBody.books().get(2).openLibraryWorkId()).isEqualTo("OL3W");
    assertThat(responseBody.books().get(2).finishedDate()).isEqualTo("2025-11-02");
    assertThat(responseBody.rollingTwelveMonthCount()).isEqualTo(3);
  }

  @Test
  void handleRequestShouldOnlyReturnBooksForRequestedUser() throws Exception {
    // arrange
    bookTrackerTable.putItem(buildBook("alice", "OL1W", LocalDate.of(2026, 4, 1)));
    bookTrackerTable.putItem(buildBook("bob", "OL2W", LocalDate.of(2026, 4, 2)));

    // act
    var response = findBooksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    var responseBody =
        objectMapper.readValue(response.getBody(), FindBooksHandler.FindBooksResponse.class);
    assertThat(responseBody.books()).hasSize(1);
    assertThat(responseBody.books().get(0).openLibraryWorkId()).isEqualTo("OL1W");
    assertThat(responseBody.rollingTwelveMonthCount()).isEqualTo(1);
  }

  @Test
  void handleRequestShouldIncludeBookExactlyAtCutoffBoundary() throws Exception {
    // arrange (today is 2026-05-04, cutoff is today - 365 days = 2025-05-04)
    var cutoffDate = TODAY_DATE.minusDays(365);
    assertThat(cutoffDate).isEqualTo(LocalDate.of(2025, 5, 4));

    bookTrackerTable.putItem(buildBook("alice", "OL1W", cutoffDate));

    // act
    var response = findBooksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    var responseBody =
        objectMapper.readValue(response.getBody(), FindBooksHandler.FindBooksResponse.class);
    assertThat(responseBody.books()).hasSize(1);
    assertThat(responseBody.rollingTwelveMonthCount())
        .as("books finished exactly 365 days ago must be inside the rolling window")
        .isEqualTo(1);
  }

  @Test
  void handleRequestShouldExcludeBookOneDayBeforeCutoffBoundary() throws Exception {
    // arrange (today is 2026-05-04, cutoff is 2025-05-04, one day before is 2025-05-03)
    var beforeCutoff = TODAY_DATE.minusDays(366);
    assertThat(beforeCutoff).isEqualTo(LocalDate.of(2025, 5, 3));

    bookTrackerTable.putItem(buildBook("alice", "OL1W", beforeCutoff));

    // act
    var response = findBooksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    var responseBody =
        objectMapper.readValue(response.getBody(), FindBooksHandler.FindBooksResponse.class);
    assertThat(responseBody.books())
        .as("books outside the rolling window are still listed")
        .hasSize(1);
    assertThat(responseBody.rollingTwelveMonthCount())
        .as("books finished one day before cutoff must be excluded")
        .isZero();
  }

  @Test
  void handleRequestShouldIncludeBookExactlyOnTodayUpperBoundary() throws Exception {
    // arrange
    bookTrackerTable.putItem(buildBook("alice", "OL1W", TODAY_DATE));

    // act
    var response = findBooksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    var responseBody =
        objectMapper.readValue(response.getBody(), FindBooksHandler.FindBooksResponse.class);
    assertThat(responseBody.rollingTwelveMonthCount())
        .as("books finished today must be inside the rolling window")
        .isEqualTo(1);
  }

  @Test
  void handleRequestShouldExcludeBookFinishedAfterToday() throws Exception {
    // arrange
    bookTrackerTable.putItem(buildBook("alice", "OL1W", TODAY_DATE.plusDays(1)));

    // act
    var response = findBooksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    var responseBody =
        objectMapper.readValue(response.getBody(), FindBooksHandler.FindBooksResponse.class);
    assertThat(responseBody.books())
        .as("future-dated books are still listed in the timeline")
        .hasSize(1);
    assertThat(responseBody.rollingTwelveMonthCount())
        .as("future-dated books must be excluded from the rolling count")
        .isZero();
  }

  @Test
  void handleRequestShouldComputeRollingCountAcrossMixOfInsideAndOutsideWindow() throws Exception {
    // arrange (today is 2026-05-04)
    bookTrackerTable.putItem(buildBook("alice", "OL1W", LocalDate.of(2026, 5, 4))); // today
    bookTrackerTable.putItem(buildBook("alice", "OL2W", LocalDate.of(2026, 1, 1))); // inside
    bookTrackerTable.putItem(buildBook("alice", "OL3W", LocalDate.of(2025, 5, 4))); // boundary
    bookTrackerTable.putItem(buildBook("alice", "OL4W", LocalDate.of(2025, 5, 3))); // outside
    bookTrackerTable.putItem(buildBook("alice", "OL5W", LocalDate.of(2024, 1, 1))); // outside

    // act
    var response = findBooksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    var responseBody =
        objectMapper.readValue(response.getBody(), FindBooksHandler.FindBooksResponse.class);
    assertThat(responseBody.books()).hasSize(5);
    assertThat(responseBody.rollingTwelveMonthCount()).isEqualTo(3);
  }

  @Test
  void handleRequestShouldReturnNullableFieldsAsNullWhenNotSet() throws Exception {
    // arrange
    var item =
        BookTrackerItem.create(
            "alice",
            "OL27448W",
            "The Lord of the Rings",
            List.of("J.R.R. Tolkien"),
            null,
            null,
            null,
            LocalDate.of(2026, 4, 1),
            TODAY,
            TODAY);
    bookTrackerTable.putItem(item);

    // act
    var response = findBooksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    var responseBody =
        objectMapper.readValue(response.getBody(), FindBooksHandler.FindBooksResponse.class);
    assertThat(responseBody.books()).hasSize(1);
    var book = responseBody.books().get(0);
    assertThat(book.coverUrl()).isNull();
    assertThat(book.pageCount()).isNull();
    assertThat(book.publicationYear()).isNull();
  }

  @Test
  void handleRequestShouldUseSecondBoundaryFromClockUtc() throws Exception {
    // arrange (clock just before midnight UTC means today is still 2026-05-04)
    fakeClock.setTime(TODAY_DATE.atTime(23, 59, 59).toInstant(ZoneOffset.UTC));
    bookTrackerTable.putItem(buildBook("alice", "OL1W", LocalDate.of(2025, 5, 4)));

    // act
    var response = findBooksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    var responseBody =
        objectMapper.readValue(response.getBody(), FindBooksHandler.FindBooksResponse.class);
    assertThat(responseBody.rollingTwelveMonthCount()).isEqualTo(1);

    // arrange (advance to the next UTC day; cutoff shifts to 2025-05-05, excluding the book)
    fakeClock.setTime(TODAY_DATE.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

    // act
    var nextDayResponse = findBooksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    var nextDayBody =
        objectMapper.readValue(nextDayResponse.getBody(), FindBooksHandler.FindBooksResponse.class);
    assertThat(nextDayBody.rollingTwelveMonthCount()).isZero();
  }
}
