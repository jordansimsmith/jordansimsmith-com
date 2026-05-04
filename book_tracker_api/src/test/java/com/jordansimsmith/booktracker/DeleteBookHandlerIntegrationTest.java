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
import software.amazon.awssdk.enhanced.dynamodb.Key;

@Testcontainers
public class DeleteBookHandlerIntegrationTest {
  private ObjectMapper objectMapper;
  private DynamoDbTable<BookTrackerItem> bookTrackerTable;

  private DeleteBookHandler deleteBookHandler;
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

    deleteBookHandler = new DeleteBookHandler(factory);
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
  void handleRequestShouldReturnNoContentWhenBookExists() throws Exception {
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
            LocalDate.of(2026, 5, 4),
            Instant.ofEpochSecond(1714809600),
            Instant.ofEpochSecond(1714809600));
    bookTrackerTable.putItem(item);

    // act
    var response = deleteBookHandler.handleRequest(buildEvent("alice", "OL27448W"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);
    assertThat(response.getBody()).isNull();

    var stored =
        bookTrackerTable.getItem(
            Key.builder()
                .partitionValue(BookTrackerItem.formatPk("alice"))
                .sortValue(BookTrackerItem.formatSk("OL27448W"))
                .build());
    assertThat(stored).isNull();

    var followUp = getBookHandler.handleRequest(buildEvent("alice", "OL27448W"), null);
    assertThat(followUp.getStatusCode()).isEqualTo(404);
  }

  @Test
  void handleRequestShouldReturnNotFoundWhenBookDoesNotExist() throws Exception {
    // act
    var response = deleteBookHandler.handleRequest(buildEvent("alice", "OL999W"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
    var errorBody =
        objectMapper.readValue(response.getBody(), DeleteBookHandler.ErrorResponse.class);
    assertThat(errorBody.message()).isEqualTo("Not Found");
  }

  @Test
  void handleRequestShouldReturnNotFoundAndPreserveBookWhenItBelongsToDifferentUser()
      throws Exception {
    // arrange
    var item =
        BookTrackerItem.create(
            "bob",
            "OL27448W",
            "Bob's Book",
            List.of(),
            null,
            null,
            null,
            LocalDate.of(2026, 1, 1),
            Instant.ofEpochSecond(1714000000),
            Instant.ofEpochSecond(1714000000));
    bookTrackerTable.putItem(item);

    // act
    var response = deleteBookHandler.handleRequest(buildEvent("alice", "OL27448W"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);

    var stillThere = bookTrackerTable.scan().items().stream().toList();
    assertThat(stillThere).hasSize(1);
    assertThat(stillThere.get(0).getUser()).isEqualTo("bob");
  }
}
