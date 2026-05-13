package com.jordansimsmith.japanesedictionary;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.JsonNode;
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
    var event = buildEvent("alice", null);

    // act
    var response = findBookmarksHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = parseBody(response.getBody());
    assertThat(body.bookmarks()).isEmpty();
  }

  @Test
  void handleRequestShouldReturnBookmarksOrderedByCreatedAtDescending() throws Exception {
    // arrange
    bookmarkTable.putItem(BookmarkItem.create("alice", 1L, Instant.ofEpochSecond(100L)));
    bookmarkTable.putItem(BookmarkItem.create("alice", 2L, Instant.ofEpochSecond(300L)));
    bookmarkTable.putItem(BookmarkItem.create("alice", 3L, Instant.ofEpochSecond(200L)));

    // act
    var response = findBookmarksHandler.handleRequest(buildEvent("alice", null), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = parseBody(response.getBody());
    assertThat(body.bookmarks())
        .extracting(FindBookmarksHandler.Bookmark::sequence)
        .containsExactly(2L, 3L, 1L);
    assertThat(body.bookmarks().get(0).createdAt()).isEqualTo(300L);
    assertThat(body.bookmarks().get(0).expression()).isNull();
    assertThat(isJsonNull(body.bookmarks().get(0).glossaryRaw())).isTrue();
  }

  @Test
  void handleRequestShouldUseSequenceAscAsTieBreakerForIdenticalCreatedAt() throws Exception {
    // arrange
    var sameTime = Instant.ofEpochSecond(500L);
    bookmarkTable.putItem(BookmarkItem.create("alice", 50L, sameTime));
    bookmarkTable.putItem(BookmarkItem.create("alice", 5L, sameTime));
    bookmarkTable.putItem(BookmarkItem.create("alice", 500L, sameTime));

    // act
    var response = findBookmarksHandler.handleRequest(buildEvent("alice", null), null);

    // assert
    var body = parseBody(response.getBody());
    assertThat(body.bookmarks())
        .extracting(FindBookmarksHandler.Bookmark::sequence)
        .containsExactly(5L, 50L, 500L);
  }

  @Test
  void handleRequestShouldOnlyReturnBookmarksOwnedByCallingUser() throws Exception {
    // arrange
    bookmarkTable.putItem(BookmarkItem.create("alice", 100L, Instant.ofEpochSecond(100L)));
    bookmarkTable.putItem(BookmarkItem.create("alice", 200L, Instant.ofEpochSecond(200L)));
    bookmarkTable.putItem(BookmarkItem.create("bob", 300L, Instant.ofEpochSecond(300L)));

    // act
    var aliceResponse = findBookmarksHandler.handleRequest(buildEvent("alice", null), null);
    var bobResponse = findBookmarksHandler.handleRequest(buildEvent("bob", null), null);

    // assert
    var aliceBody = parseBody(aliceResponse.getBody());
    assertThat(aliceBody.bookmarks())
        .extracting(FindBookmarksHandler.Bookmark::sequence)
        .containsExactly(200L, 100L);

    var bobBody = parseBody(bobResponse.getBody());
    assertThat(bobBody.bookmarks())
        .extracting(FindBookmarksHandler.Bookmark::sequence)
        .containsExactly(300L);
  }

  @Test
  void handleRequestShouldIgnoreTermRowsInSameTable() throws Exception {
    // arrange
    var termItem = TermItem.create(999L, "猫", "ねこ", "neko", 100, 0, "{\"tag\":\"div\"}");
    termTable.putItem(termItem);
    bookmarkTable.putItem(BookmarkItem.create("alice", 42L, Instant.ofEpochSecond(123L)));

    // act
    var response = findBookmarksHandler.handleRequest(buildEvent("alice", null), null);

    // assert
    var body = parseBody(response.getBody());
    assertThat(body.bookmarks())
        .extracting(FindBookmarksHandler.Bookmark::sequence)
        .containsExactly(42L);
  }

  @Test
  void handleRequestShouldHydrateTermFieldsWhenIncludeTermIsSet() throws Exception {
    // arrange
    termTable.putItem(
        TermItem.create(
            1316830L,
            "新橋",
            "しんばし",
            "shinbashi",
            18472,
            0,
            "{\"tag\":\"div\",\"content\":\"Shinbashi\"}"));
    bookmarkTable.putItem(BookmarkItem.create("alice", 1316830L, Instant.ofEpochSecond(500L)));

    // act
    var response = findBookmarksHandler.handleRequest(buildEvent("alice", "term"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = parseBody(response.getBody());
    assertThat(body.bookmarks()).hasSize(1);
    var bookmark = body.bookmarks().get(0);
    assertThat(bookmark.sequence()).isEqualTo(1316830L);
    assertThat(bookmark.createdAt()).isEqualTo(500L);
    assertThat(bookmark.expression()).isEqualTo("新橋");
    assertThat(bookmark.reading()).isEqualTo("しんばし");
    assertThat(bookmark.readingRomaji()).isEqualTo("shinbashi");
    assertThat(bookmark.frequencyRank()).isEqualTo(18472);
    assertThat(bookmark.pitch()).isEqualTo(0);
    assertThat(bookmark.glossaryRaw().get("tag").asText()).isEqualTo("div");
    assertThat(bookmark.glossaryRaw().get("content").asText()).isEqualTo("Shinbashi");
  }

  @Test
  void handleRequestShouldDropDanglingBookmarksWhenIncludeTermIsSet() throws Exception {
    // arrange
    termTable.putItem(TermItem.create(1L, "新", "しん", "shin", null, 0, "{\"tag\":\"div\"}"));
    bookmarkTable.putItem(BookmarkItem.create("alice", 1L, Instant.ofEpochSecond(200L)));
    bookmarkTable.putItem(BookmarkItem.create("alice", 999L, Instant.ofEpochSecond(100L)));

    // act
    var response = findBookmarksHandler.handleRequest(buildEvent("alice", "term"), null);

    // assert
    var body = parseBody(response.getBody());
    assertThat(body.bookmarks())
        .extracting(FindBookmarksHandler.Bookmark::sequence)
        .containsExactly(1L);
  }

  @Test
  void handleRequestShouldReturnEmptyWhenIncludeTermIsSetAndUserHasNoBookmarks() throws Exception {
    // arrange
    var event = buildEvent("alice", "term");

    // act
    var response = findBookmarksHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = parseBody(response.getBody());
    assertThat(body.bookmarks()).isEmpty();
  }

  @Test
  void handleRequestShouldRejectUnknownIncludeValue() throws Exception {
    // arrange
    var event = buildEvent("alice", "audio");

    // act
    var response = findBookmarksHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var body = objectMapper.readValue(response.getBody(), FindBookmarksHandler.ErrorResponse.class);
    assertThat(body.message()).isEqualTo("include parameter must be 'term' or omitted");
  }

  private FindBookmarksHandler.FindBookmarksResponse parseBody(String body) throws Exception {
    return objectMapper.readValue(body, FindBookmarksHandler.FindBookmarksResponse.class);
  }

  private static boolean isJsonNull(JsonNode node) {
    return node == null || node.isNull();
  }

  private static APIGatewayV2HTTPEvent buildEvent(String user, String include) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    var builder = APIGatewayV2HTTPEvent.builder().withHeaders(Map.of("Authorization", authHeader));
    if (include != null) {
      builder.withQueryStringParameters(Map.of("include", include));
    }
    return builder.build();
  }
}
