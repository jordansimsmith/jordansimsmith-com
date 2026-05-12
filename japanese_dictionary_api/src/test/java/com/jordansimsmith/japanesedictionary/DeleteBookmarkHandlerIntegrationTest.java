package com.jordansimsmith.japanesedictionary;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class DeleteBookmarkHandlerIntegrationTest {
  private ObjectMapper objectMapper;
  private DynamoDbTable<BookmarkItem> bookmarkTable;
  private DynamoDbTable<TermItem> termTable;

  private DeleteBookmarkHandler deleteBookmarkHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = JapaneseDictionaryTestFactory.create(dynamoDbContainer.getEndpoint());
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.termTable());
  }

  @BeforeEach
  void setUp() {
    var factory = JapaneseDictionaryTestFactory.create(dynamoDbContainer.getEndpoint());

    objectMapper = factory.objectMapper();
    bookmarkTable = factory.bookmarkTable();
    termTable = factory.termTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    deleteBookmarkHandler = new DeleteBookmarkHandler(factory);
  }

  @Test
  void handleRequestShouldRemoveExistingBookmarkAndReturnNoContent() throws Exception {
    // arrange
    bookmarkTable.putItem(BookmarkItem.create("alice", 1316830L, Instant.ofEpochSecond(100L)));

    // act
    var response = deleteBookmarkHandler.handleRequest(buildEvent("alice", "1316830"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
    assertThat(bookmarkTable.scan().items().stream().toList()).isEmpty();
  }

  @Test
  void handleRequestShouldBeIdempotentWhenBookmarkDoesNotExist() throws Exception {
    // arrange
    var event = buildEvent("alice", "42");

    // act
    var first = deleteBookmarkHandler.handleRequest(event, null);
    var second = deleteBookmarkHandler.handleRequest(event, null);

    // assert
    assertThat(first.getStatusCode()).isEqualTo(204);
    assertThat(second.getStatusCode()).isEqualTo(204);
    assertThat(bookmarkTable.scan().items().stream().toList()).isEmpty();
  }

  @Test
  void handleRequestShouldRejectNonNumericSequence() throws Exception {
    // arrange
    var event = buildEvent("alice", "abc");

    // act
    var response = deleteBookmarkHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var body =
        objectMapper.readValue(response.getBody(), DeleteBookmarkHandler.ErrorResponse.class);
    assertThat(body.message()).isEqualTo("sequence must be a positive integer");
  }

  @Test
  void handleRequestShouldRejectZeroSequence() throws Exception {
    // arrange
    var event = buildEvent("alice", "0");

    // act
    var response = deleteBookmarkHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var body =
        objectMapper.readValue(response.getBody(), DeleteBookmarkHandler.ErrorResponse.class);
    assertThat(body.message()).isEqualTo("sequence must be a positive integer");
  }

  @Test
  void handleRequestShouldRejectNegativeSequence() throws Exception {
    // arrange
    var event = buildEvent("alice", "-7");

    // act
    var response = deleteBookmarkHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void handleRequestShouldOnlyDeleteBookmarksOwnedByCallingUser() throws Exception {
    // arrange
    bookmarkTable.putItem(BookmarkItem.create("alice", 100L, Instant.ofEpochSecond(100L)));
    bookmarkTable.putItem(BookmarkItem.create("bob", 100L, Instant.ofEpochSecond(200L)));

    // act
    var response = deleteBookmarkHandler.handleRequest(buildEvent("bob", "100"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);
    var remaining = bookmarkTable.scan().items().stream().toList();
    assertThat(remaining).hasSize(1);
    assertThat(remaining.get(0).getUser()).isEqualTo("alice");
    assertThat(remaining.get(0).getSequence()).isEqualTo(100L);
  }

  @Test
  void handleRequestShouldNotTouchTermRows() throws Exception {
    // arrange
    var termItem =
        TermItem.create(1316830L, "新橋", "しんばし", "shinbashi", 18472, 0, "{\"tag\":\"div\"}");
    termTable.putItem(termItem);
    bookmarkTable.putItem(BookmarkItem.create("alice", 1316830L, Instant.ofEpochSecond(100L)));

    // act
    deleteBookmarkHandler.handleRequest(buildEvent("alice", "1316830"), null);

    // assert
    var preserved =
        termTable.getItem(
            r ->
                r.key(
                    k ->
                        k.partitionValue(TermItem.formatPk(1316830L))
                            .sortValue(TermItem.formatSk(1316830L))));
    assertThat(preserved).isEqualTo(termItem);
  }

  private static APIGatewayV2HTTPEvent buildEvent(String user, String sequence) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withPathParameters(Map.of("sequence", sequence))
        .build();
  }
}
