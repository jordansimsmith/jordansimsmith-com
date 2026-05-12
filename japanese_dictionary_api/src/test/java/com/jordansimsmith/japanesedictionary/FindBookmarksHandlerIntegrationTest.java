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
public class FindBookmarksHandlerIntegrationTest {
  private ObjectMapper objectMapper;
  private DynamoDbTable<BookmarkItem> bookmarkTable;
  private DynamoDbTable<TermItem> termTable;

  private FindBookmarksHandler findBookmarksHandler;

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

    findBookmarksHandler = new FindBookmarksHandler(factory);
  }

  @Test
  void handleRequestShouldReturnEmptyListWhenUserHasNoBookmarks() throws Exception {
    // arrange
    var event = buildEvent("alice");

    // act
    var response = findBookmarksHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body =
        objectMapper.readValue(
            response.getBody(), FindBookmarksHandler.FindBookmarksResponse.class);
    assertThat(body.sequences()).isEmpty();
  }

  @Test
  void handleRequestShouldReturnSequencesOrderedByCreatedAtDescending() throws Exception {
    // arrange
    bookmarkTable.putItem(BookmarkItem.create("alice", 1L, Instant.ofEpochSecond(100L)));
    bookmarkTable.putItem(BookmarkItem.create("alice", 2L, Instant.ofEpochSecond(300L)));
    bookmarkTable.putItem(BookmarkItem.create("alice", 3L, Instant.ofEpochSecond(200L)));

    // act
    var response = findBookmarksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body =
        objectMapper.readValue(
            response.getBody(), FindBookmarksHandler.FindBookmarksResponse.class);
    assertThat(body.sequences()).containsExactly(2L, 3L, 1L);
  }

  @Test
  void handleRequestShouldUseSequenceAscAsTieBreakerForIdenticalCreatedAt() throws Exception {
    // arrange
    var sameTime = Instant.ofEpochSecond(500L);
    bookmarkTable.putItem(BookmarkItem.create("alice", 50L, sameTime));
    bookmarkTable.putItem(BookmarkItem.create("alice", 5L, sameTime));
    bookmarkTable.putItem(BookmarkItem.create("alice", 500L, sameTime));

    // act
    var response = findBookmarksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    var body =
        objectMapper.readValue(
            response.getBody(), FindBookmarksHandler.FindBookmarksResponse.class);
    assertThat(body.sequences()).containsExactly(5L, 50L, 500L);
  }

  @Test
  void handleRequestShouldOnlyReturnBookmarksOwnedByCallingUser() throws Exception {
    // arrange
    bookmarkTable.putItem(BookmarkItem.create("alice", 100L, Instant.ofEpochSecond(100L)));
    bookmarkTable.putItem(BookmarkItem.create("alice", 200L, Instant.ofEpochSecond(200L)));
    bookmarkTable.putItem(BookmarkItem.create("bob", 300L, Instant.ofEpochSecond(300L)));

    // act
    var aliceResponse = findBookmarksHandler.handleRequest(buildEvent("alice"), null);
    var bobResponse = findBookmarksHandler.handleRequest(buildEvent("bob"), null);

    // assert
    var aliceBody =
        objectMapper.readValue(
            aliceResponse.getBody(), FindBookmarksHandler.FindBookmarksResponse.class);
    assertThat(aliceBody.sequences()).containsExactly(200L, 100L);

    var bobBody =
        objectMapper.readValue(
            bobResponse.getBody(), FindBookmarksHandler.FindBookmarksResponse.class);
    assertThat(bobBody.sequences()).containsExactly(300L);
  }

  @Test
  void handleRequestShouldIgnoreTermRowsInSameTable() throws Exception {
    // arrange
    var termItem = TermItem.create(999L, "猫", "ねこ", "neko", 100, 0, "{\"tag\":\"div\"}");
    termTable.putItem(termItem);
    bookmarkTable.putItem(BookmarkItem.create("alice", 42L, Instant.ofEpochSecond(123L)));

    // act
    var response = findBookmarksHandler.handleRequest(buildEvent("alice"), null);

    // assert
    var body =
        objectMapper.readValue(
            response.getBody(), FindBookmarksHandler.FindBookmarksResponse.class);
    assertThat(body.sequences()).containsExactly(42L);
  }

  private static APIGatewayV2HTTPEvent buildEvent(String user) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder().withHeaders(Map.of("Authorization", authHeader)).build();
  }
}
