package com.jordansimsmith.booktracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
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

@Testcontainers
public class GetBookHandlerIntegrationTest {
  private ObjectMapper objectMapper;
  private DynamoDbTable<BookTrackerItem> bookTrackerTable;

  private GetBookHandler getBookHandler;

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

    objectMapper = factory.objectMapper();
    bookTrackerTable = factory.bookTrackerTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    getBookHandler = new GetBookHandler(factory);
  }

  private APIGatewayV2HTTPEvent buildEvent(String user, String workId) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withPathParameters(Map.of("open_library_work_id", workId))
        .build();
  }

  @Test
  void handleRequestShouldReturnBookWhenExists() throws Exception {
    // arrange
    var item =
        BookTrackerItem.create(
            "alice",
            "OL27448W",
            "The Lord of the Rings",
            List.of("J.R.R. Tolkien"),
            "https://covers.openlibrary.org/b/id/14625765-L.jpg",
            1193,
            1954,
            LocalDate.of(2026, 5, 4),
            Instant.ofEpochSecond(1714809600),
            Instant.ofEpochSecond(1714895999));
    bookTrackerTable.putItem(item);

    // act
    var response = getBookHandler.handleRequest(buildEvent("alice", "OL27448W"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(response.getHeaders())
        .containsEntry("Content-Type", "application/json; charset=utf-8");

    var responseBody =
        objectMapper.readValue(response.getBody(), GetBookHandler.GetBookResponse.class);
    assertThat(responseBody.book().openLibraryWorkId()).isEqualTo("OL27448W");
    assertThat(responseBody.book().title()).isEqualTo("The Lord of the Rings");
    assertThat(responseBody.book().authors()).containsExactly("J.R.R. Tolkien");
    assertThat(responseBody.book().coverUrl())
        .isEqualTo("https://covers.openlibrary.org/b/id/14625765-L.jpg");
    assertThat(responseBody.book().pageCount()).isEqualTo(1193);
    assertThat(responseBody.book().publicationYear()).isEqualTo(1954);
    assertThat(responseBody.book().finishedDate()).isEqualTo("2026-05-04");
    assertThat(responseBody.book().createdAt()).isEqualTo(1714809600);
    assertThat(responseBody.book().updatedAt()).isEqualTo(1714895999);
  }

  @Test
  void handleRequestShouldReturnNotFoundWhenBookDoesNotExist() throws Exception {
    // act
    var response = getBookHandler.handleRequest(buildEvent("alice", "OL999999W"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
    var errorBody = objectMapper.readValue(response.getBody(), GetBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("Not Found");
  }

  @Test
  void handleRequestShouldReturnNotFoundWhenBookBelongsToDifferentUser() throws Exception {
    // arrange
    var item =
        BookTrackerItem.create(
            "bob",
            "OL27448W",
            "The Lord of the Rings",
            List.of("J.R.R. Tolkien"),
            null,
            null,
            null,
            LocalDate.of(2026, 5, 4),
            Instant.ofEpochSecond(1714809600),
            Instant.ofEpochSecond(1714809600));
    bookTrackerTable.putItem(item);

    // act
    var response = getBookHandler.handleRequest(buildEvent("alice", "OL27448W"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
    var errorBody = objectMapper.readValue(response.getBody(), GetBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("Not Found");
  }
}
