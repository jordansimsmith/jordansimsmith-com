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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

@Testcontainers
public class UpdateBookHandlerIntegrationTest {
  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<BookTrackerItem> bookTrackerTable;

  private UpdateBookHandler updateBookHandler;

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
    objectMapper = factory.objectMapper();
    bookTrackerTable = factory.bookTrackerTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    updateBookHandler = new UpdateBookHandler(factory);
  }

  private APIGatewayV2HTTPEvent buildEvent(String user, String workId, String body) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withPathParameters(Map.of("open_library_work_id", workId))
        .withBody(body)
        .build();
  }

  @Test
  void handleRequestShouldUpdateFinishedDateAndRewriteGsi1sk() throws Exception {
    // arrange
    var createdAt = Instant.ofEpochSecond(1700000000);
    var updatedAt = Instant.ofEpochSecond(1700100000);
    fakeClock.setTime(updatedAt);

    var existing =
        BookTrackerItem.create(
            "alice",
            "OL27448W",
            "The Lord of the Rings",
            List.of("J.R.R. Tolkien"),
            "https://covers.openlibrary.org/b/id/14625765-L.jpg",
            1193,
            1954,
            LocalDate.of(2026, 4, 15),
            createdAt,
            createdAt);
    bookTrackerTable.putItem(existing);

    var requestBody =
        """
        {"finished_date": "2026-05-04"}
        """;

    // act
    var response =
        updateBookHandler.handleRequest(buildEvent("alice", "OL27448W", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8");

    var responseBody =
        objectMapper.readValue(response.getBody(), UpdateBookHandler.UpdateBookResponse.class);
    assertThat(responseBody.book().openLibraryWorkId()).isEqualTo("OL27448W");
    assertThat(responseBody.book().title()).isEqualTo("The Lord of the Rings");
    assertThat(responseBody.book().authors()).containsExactly("J.R.R. Tolkien");
    assertThat(responseBody.book().coverUrl())
        .isEqualTo("https://covers.openlibrary.org/b/id/14625765-L.jpg");
    assertThat(responseBody.book().pageCount()).isEqualTo(1193);
    assertThat(responseBody.book().publicationYear()).isEqualTo(1954);
    assertThat(responseBody.book().finishedDate()).isEqualTo("2026-05-04");
    assertThat(responseBody.book().createdAt()).isEqualTo(createdAt.getEpochSecond());
    assertThat(responseBody.book().updatedAt()).isEqualTo(updatedAt.getEpochSecond());

    var stored = bookTrackerTable.scan().items().stream().toList();
    assertThat(stored).hasSize(1);
    var storedItem = stored.get(0);
    assertThat(storedItem.getFinishedDate()).isEqualTo(LocalDate.of(2026, 5, 4));
    assertThat(storedItem.getGsi1sk()).isEqualTo("FINISHED#2026-05-04#BOOK#OL27448W");
    assertThat(storedItem.getCreatedAt()).isEqualTo(createdAt);
    assertThat(storedItem.getUpdatedAt()).isEqualTo(updatedAt);
    assertThat(storedItem.getTitle()).isEqualTo("The Lord of the Rings");
  }

  @Test
  void handleRequestShouldReorderTimelineWhenFinishedDateMovesToNewerMonth() throws Exception {
    // arrange
    var now = Instant.ofEpochSecond(1700000000);
    fakeClock.setTime(now);

    bookTrackerTable.putItem(
        BookTrackerItem.create(
            "alice",
            "OLAW",
            "Older Read",
            List.of(),
            null,
            null,
            null,
            LocalDate.of(2026, 2, 1),
            now,
            now));
    bookTrackerTable.putItem(
        BookTrackerItem.create(
            "alice",
            "OLBW",
            "Newer Read",
            List.of(),
            null,
            null,
            null,
            LocalDate.of(2026, 3, 1),
            now,
            now));

    var requestBody =
        """
        {"finished_date": "2026-04-15"}
        """;

    // act
    var response = updateBookHandler.handleRequest(buildEvent("alice", "OLAW", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);

    var gsi1 = bookTrackerTable.index(BookTrackerItem.GSI1_NAME);
    var query =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(
                    k -> k.partitionValue(BookTrackerItem.formatGsi1pk("alice"))))
            .scanIndexForward(false)
            .build();
    var ordered =
        gsi1.query(query).stream()
            .flatMap(p -> p.items().stream())
            .map(BookTrackerItem::getOpenLibraryWorkId)
            .toList();

    assertThat(ordered)
        .as("the updated book should now sort first under the new finished_date")
        .containsExactly("OLAW", "OLBW");
  }

  @Test
  void handleRequestShouldReturnNotFoundWhenBookDoesNotExist() throws Exception {
    // arrange
    var requestBody =
        """
        {"finished_date": "2026-05-04"}
        """;

    // act
    var response =
        updateBookHandler.handleRequest(buildEvent("alice", "OL999W", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
    var errorBody =
        objectMapper.readValue(response.getBody(), UpdateBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("Not Found");
  }

  @Test
  void handleRequestShouldReturnNotFoundWhenBookBelongsToDifferentUser() throws Exception {
    // arrange
    var now = Instant.ofEpochSecond(1700000000);
    fakeClock.setTime(now);
    bookTrackerTable.putItem(
        BookTrackerItem.create(
            "bob",
            "OL27448W",
            "Bob's Book",
            List.of(),
            null,
            null,
            null,
            LocalDate.of(2026, 1, 1),
            now,
            now));

    var requestBody =
        """
        {"finished_date": "2026-05-04"}
        """;

    // act
    var response =
        updateBookHandler.handleRequest(buildEvent("alice", "OL27448W", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenFinishedDateMissing() throws Exception {
    // arrange
    var now = Instant.ofEpochSecond(1700000000);
    fakeClock.setTime(now);
    bookTrackerTable.putItem(
        BookTrackerItem.create(
            "alice",
            "OL27448W",
            "Title",
            List.of(),
            null,
            null,
            null,
            LocalDate.of(2026, 4, 1),
            now,
            now));

    var requestBody = "{}";

    // act
    var response =
        updateBookHandler.handleRequest(buildEvent("alice", "OL27448W", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var errorBody =
        objectMapper.readValue(response.getBody(), UpdateBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("finished_date is required");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenFinishedDateMalformed() throws Exception {
    // arrange
    var now = Instant.ofEpochSecond(1700000000);
    fakeClock.setTime(now);
    bookTrackerTable.putItem(
        BookTrackerItem.create(
            "alice",
            "OL27448W",
            "Title",
            List.of(),
            null,
            null,
            null,
            LocalDate.of(2026, 4, 1),
            now,
            now));

    var requestBody =
        """
        {"finished_date": "04/05/2026"}
        """;

    // act
    var response =
        updateBookHandler.handleRequest(buildEvent("alice", "OL27448W", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var errorBody =
        objectMapper.readValue(response.getBody(), UpdateBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("finished_date must be in YYYY-MM-DD format");
  }

  @Test
  void handleRequestShouldIgnoreOtherFieldsInRequestBody() throws Exception {
    // arrange
    var createdAt = Instant.ofEpochSecond(1700000000);
    var updatedAt = Instant.ofEpochSecond(1700100000);
    fakeClock.setTime(updatedAt);

    bookTrackerTable.putItem(
        BookTrackerItem.create(
            "alice",
            "OL27448W",
            "Original Title",
            List.of("Original Author"),
            "https://example.com/original.jpg",
            500,
            1990,
            LocalDate.of(2026, 4, 1),
            createdAt,
            createdAt));

    var requestBody =
        """
        {
          "finished_date": "2026-05-04",
          "title": "Hacked Title",
          "authors": ["Mallory"],
          "cover_url": "https://example.com/hacked.jpg",
          "page_count": 1,
          "publication_year": 1
        }
        """;

    // act
    var response =
        updateBookHandler.handleRequest(buildEvent("alice", "OL27448W", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);

    var stored = bookTrackerTable.scan().items().stream().toList();
    assertThat(stored).hasSize(1);
    var storedItem = stored.get(0);
    assertThat(storedItem.getTitle()).isEqualTo("Original Title");
    assertThat(storedItem.getAuthors()).containsExactly("Original Author");
    assertThat(storedItem.getCoverUrl()).isEqualTo("https://example.com/original.jpg");
    assertThat(storedItem.getPageCount()).isEqualTo(500);
    assertThat(storedItem.getPublicationYear()).isEqualTo(1990);
    assertThat(storedItem.getFinishedDate()).isEqualTo(LocalDate.of(2026, 5, 4));
  }
}
