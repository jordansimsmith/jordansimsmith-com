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
public class CreateBookHandlerIntegrationTest {
  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<BookTrackerItem> bookTrackerTable;

  private CreateBookHandler createBookHandler;

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
    fakeClock.setTime(LocalDate.of(2026, 5, 4).atStartOfDay().toInstant(ZoneOffset.UTC));
    objectMapper = factory.objectMapper();
    bookTrackerTable = factory.bookTrackerTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    createBookHandler = new CreateBookHandler(factory);
  }

  private APIGatewayV2HTTPEvent buildEvent(String user, String body) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withBody(body)
        .build();
  }

  @Test
  void handleRequestShouldCreateBookAndStoreInDynamoDb() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1714809600));
    var requestBody =
        """
        {
          "open_library_work_id": "OL27448W",
          "title": "The Lord of the Rings",
          "authors": ["J.R.R. Tolkien"],
          "cover_url": "https://covers.openlibrary.org/b/id/14625765-L.jpg",
          "page_count": 1193,
          "publication_year": 1954,
          "finished_date": "2026-05-04"
        }
        """;

    // act
    var response = createBookHandler.handleRequest(buildEvent("alice", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(201);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8");

    var responseBody =
        objectMapper.readValue(response.getBody(), CreateBookHandler.CreateBookResponse.class);
    assertThat(responseBody.book()).isNotNull();
    assertThat(responseBody.book().openLibraryWorkId()).isEqualTo("OL27448W");
    assertThat(responseBody.book().title()).isEqualTo("The Lord of the Rings");
    assertThat(responseBody.book().authors()).containsExactly("J.R.R. Tolkien");
    assertThat(responseBody.book().coverUrl())
        .isEqualTo("https://covers.openlibrary.org/b/id/14625765-L.jpg");
    assertThat(responseBody.book().pageCount()).isEqualTo(1193);
    assertThat(responseBody.book().publicationYear()).isEqualTo(1954);
    assertThat(responseBody.book().finishedDate()).isEqualTo("2026-05-04");
    assertThat(responseBody.book().createdAt()).isEqualTo(1714809600L);
    assertThat(responseBody.book().updatedAt()).isEqualTo(1714809600L);

    var items = bookTrackerTable.scan().items().stream().toList();
    assertThat(items).hasSize(1);

    var storedItem = items.get(0);
    assertThat(storedItem.getPk()).isEqualTo("USER#alice");
    assertThat(storedItem.getSk()).isEqualTo("BOOK#OL27448W");
    assertThat(storedItem.getGsi1pk()).isEqualTo("USER#alice");
    assertThat(storedItem.getGsi1sk()).isEqualTo("FINISHED#2026-05-04#BOOK#OL27448W");
    assertThat(storedItem.getUser()).isEqualTo("alice");
    assertThat(storedItem.getOpenLibraryWorkId()).isEqualTo("OL27448W");
    assertThat(storedItem.getTitle()).isEqualTo("The Lord of the Rings");
    assertThat(storedItem.getAuthors()).containsExactly("J.R.R. Tolkien");
    assertThat(storedItem.getCoverUrl())
        .isEqualTo("https://covers.openlibrary.org/b/id/14625765-L.jpg");
    assertThat(storedItem.getPageCount()).isEqualTo(1193);
    assertThat(storedItem.getPublicationYear()).isEqualTo(1954);
    assertThat(storedItem.getFinishedDate()).isEqualTo(LocalDate.of(2026, 5, 4));
    assertThat(storedItem.getCreatedAt()).isEqualTo(Instant.ofEpochSecond(1714809600));
    assertThat(storedItem.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1714809600));
  }

  @Test
  void handleRequestShouldDefaultAuthorsToEmptyListWhenMissing() throws Exception {
    // arrange
    var requestBody =
        """
        {
          "open_library_work_id": "OL27448W",
          "title": "The Lord of the Rings",
          "finished_date": "2026-05-04"
        }
        """;

    // act
    var response = createBookHandler.handleRequest(buildEvent("alice", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(201);

    var responseBody =
        objectMapper.readValue(response.getBody(), CreateBookHandler.CreateBookResponse.class);
    assertThat(responseBody.book().authors()).isEmpty();
    assertThat(responseBody.book().coverUrl()).isNull();
    assertThat(responseBody.book().pageCount()).isNull();
    assertThat(responseBody.book().publicationYear()).isNull();

    var items = bookTrackerTable.scan().items().stream().toList();
    assertThat(items).hasSize(1);
    assertThat(items.get(0).getAuthors()).isEmpty();
    assertThat(items.get(0).getCoverUrl()).isNull();
    assertThat(items.get(0).getPageCount()).isNull();
    assertThat(items.get(0).getPublicationYear()).isNull();
  }

  @Test
  void handleRequestShouldReturnConflictWhenBookAlreadyExists() throws Exception {
    // arrange
    var existingItem =
        BookTrackerItem.create(
            "alice",
            "OL27448W",
            "The Lord of the Rings",
            List.of("J.R.R. Tolkien"),
            null,
            null,
            null,
            LocalDate.of(2026, 4, 12),
            Instant.ofEpochSecond(1714000000),
            Instant.ofEpochSecond(1714000000));
    bookTrackerTable.putItem(existingItem);

    var requestBody =
        """
        {
          "open_library_work_id": "OL27448W",
          "title": "The Lord of the Rings",
          "authors": ["J.R.R. Tolkien"],
          "finished_date": "2026-05-04"
        }
        """;

    // act
    var response = createBookHandler.handleRequest(buildEvent("alice", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);

    var errorBody =
        objectMapper.readValue(response.getBody(), CreateBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("already added on 2026-04-12");

    var items = bookTrackerTable.scan().items().stream().toList();
    assertThat(items).hasSize(1);
    assertThat(items.get(0).getFinishedDate()).isEqualTo(LocalDate.of(2026, 4, 12));
  }

  @Test
  void handleRequestShouldAllowDuplicateWorkIdsAcrossDifferentUsers() throws Exception {
    // arrange
    var existingItem =
        BookTrackerItem.create(
            "alice",
            "OL27448W",
            "The Lord of the Rings",
            List.of("J.R.R. Tolkien"),
            null,
            null,
            null,
            LocalDate.of(2026, 4, 12),
            Instant.ofEpochSecond(1714000000),
            Instant.ofEpochSecond(1714000000));
    bookTrackerTable.putItem(existingItem);

    var requestBody =
        """
        {
          "open_library_work_id": "OL27448W",
          "title": "The Lord of the Rings",
          "finished_date": "2026-05-04"
        }
        """;

    // act
    var response = createBookHandler.handleRequest(buildEvent("bob", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(201);

    var items = bookTrackerTable.scan().items().stream().toList();
    assertThat(items).hasSize(2);
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenOpenLibraryWorkIdMissing() throws Exception {
    // arrange
    var requestBody =
        """
        {
          "title": "The Lord of the Rings",
          "finished_date": "2026-05-04"
        }
        """;

    // act
    var response = createBookHandler.handleRequest(buildEvent("alice", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var errorBody =
        objectMapper.readValue(response.getBody(), CreateBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("open_library_work_id is required");

    assertThat(bookTrackerTable.scan().items().stream().toList()).isEmpty();
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenOpenLibraryWorkIdHasWrongFormat() throws Exception {
    // arrange
    var requestBody =
        """
        {
          "open_library_work_id": "not-an-olid",
          "title": "The Lord of the Rings",
          "finished_date": "2026-05-04"
        }
        """;

    // act
    var response = createBookHandler.handleRequest(buildEvent("alice", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var errorBody =
        objectMapper.readValue(response.getBody(), CreateBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("open_library_work_id must match ^OL[0-9]+W$");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenTitleMissing() throws Exception {
    // arrange
    var requestBody =
        """
        {
          "open_library_work_id": "OL27448W",
          "finished_date": "2026-05-04"
        }
        """;

    // act
    var response = createBookHandler.handleRequest(buildEvent("alice", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var errorBody =
        objectMapper.readValue(response.getBody(), CreateBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("title is required");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenCoverUrlIsBlank() throws Exception {
    // arrange
    var requestBody =
        """
        {
          "open_library_work_id": "OL27448W",
          "title": "The Lord of the Rings",
          "cover_url": "   ",
          "finished_date": "2026-05-04"
        }
        """;

    // act
    var response = createBookHandler.handleRequest(buildEvent("alice", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var errorBody =
        objectMapper.readValue(response.getBody(), CreateBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("cover_url must not be blank");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenPageCountIsZero() throws Exception {
    // arrange
    var requestBody =
        """
        {
          "open_library_work_id": "OL27448W",
          "title": "The Lord of the Rings",
          "page_count": 0,
          "finished_date": "2026-05-04"
        }
        """;

    // act
    var response = createBookHandler.handleRequest(buildEvent("alice", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var errorBody =
        objectMapper.readValue(response.getBody(), CreateBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("page_count must be a positive integer");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenPublicationYearOutOfRange() throws Exception {
    // arrange
    var requestBody =
        """
        {
          "open_library_work_id": "OL27448W",
          "title": "The Lord of the Rings",
          "publication_year": 9999,
          "finished_date": "2026-05-04"
        }
        """;

    // act
    var response = createBookHandler.handleRequest(buildEvent("alice", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var errorBody =
        objectMapper.readValue(response.getBody(), CreateBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("publication_year must be between 0 and 2031");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenFinishedDateMissing() throws Exception {
    // arrange
    var requestBody =
        """
        {
          "open_library_work_id": "OL27448W",
          "title": "The Lord of the Rings"
        }
        """;

    // act
    var response = createBookHandler.handleRequest(buildEvent("alice", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var errorBody =
        objectMapper.readValue(response.getBody(), CreateBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("finished_date is required");
  }

  @Test
  void handleRequestShouldReturnBadRequestWhenFinishedDateMalformed() throws Exception {
    // arrange
    var requestBody =
        """
        {
          "open_library_work_id": "OL27448W",
          "title": "The Lord of the Rings",
          "finished_date": "04-05-2026"
        }
        """;

    // act
    var response = createBookHandler.handleRequest(buildEvent("alice", requestBody), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var errorBody =
        objectMapper.readValue(response.getBody(), CreateBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("finished_date must be in YYYY-MM-DD format");
  }
}
