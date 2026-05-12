package com.jordansimsmith.japanesedictionary;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.time.FakeClock;
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
public class CreateBookmarkHandlerIntegrationTest {
  private FakeClock fakeClock;
  private ObjectMapper objectMapper;
  private DynamoDbTable<BookmarkItem> bookmarkTable;
  private DynamoDbTable<TermItem> termTable;

  private CreateBookmarkHandler createBookmarkHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = JapaneseDictionaryTestFactory.create(dynamoDbContainer.getEndpoint());
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.termTable());
  }

  @BeforeEach
  void setUp() {
    var factory = JapaneseDictionaryTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    objectMapper = factory.objectMapper();
    bookmarkTable = factory.bookmarkTable();
    termTable = factory.termTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    createBookmarkHandler = new CreateBookmarkHandler(factory);
  }

  @Test
  void handleRequestShouldCreateBookmarkAndStoreInDynamoDb() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000L));
    var event = buildEvent("alice", "1316830");

    // act
    var response = createBookmarkHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(201);

    var body =
        objectMapper.readValue(
            response.getBody(), CreateBookmarkHandler.CreateBookmarkResponse.class);
    assertThat(body.sequence()).isEqualTo(1316830L);
    assertThat(body.createdAt()).isEqualTo(1700000000L);

    var stored = bookmarkTable.scan().items().stream().toList();
    assertThat(stored).hasSize(1);
    var item = stored.get(0);
    assertThat(item.getPk()).isEqualTo("USER#alice");
    assertThat(item.getSk()).isEqualTo("BOOKMARK#1316830");
    assertThat(item.getUser()).isEqualTo("alice");
    assertThat(item.getSequence()).isEqualTo(1316830L);
    assertThat(item.getCreatedAt()).isEqualTo(Instant.ofEpochSecond(1700000000L));
  }

  @Test
  void handleRequestShouldBeIdempotentAndRefreshCreatedAtOnRepeatedPut() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000L));
    var event = buildEvent("alice", "42");

    // act
    var first = createBookmarkHandler.handleRequest(event, null);
    fakeClock.setTime(Instant.ofEpochSecond(1700009999L));
    var second = createBookmarkHandler.handleRequest(event, null);

    // assert
    assertThat(first.getStatusCode()).isEqualTo(201);
    assertThat(second.getStatusCode()).isEqualTo(201);

    var stored = bookmarkTable.scan().items().stream().toList();
    assertThat(stored).hasSize(1);
    assertThat(stored.get(0).getCreatedAt()).isEqualTo(Instant.ofEpochSecond(1700009999L));
  }

  @Test
  void handleRequestShouldRejectNonNumericSequence() throws Exception {
    // arrange
    var event = buildEvent("alice", "abc");

    // act
    var response = createBookmarkHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var body =
        objectMapper.readValue(response.getBody(), CreateBookmarkHandler.ErrorResponse.class);
    assertThat(body.message()).isEqualTo("sequence must be a positive integer");
    assertThat(bookmarkTable.scan().items().stream().toList()).isEmpty();
  }

  @Test
  void handleRequestShouldRejectZeroSequence() throws Exception {
    // arrange
    var event = buildEvent("alice", "0");

    // act
    var response = createBookmarkHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
    var body =
        objectMapper.readValue(response.getBody(), CreateBookmarkHandler.ErrorResponse.class);
    assertThat(body.message()).isEqualTo("sequence must be a positive integer");
  }

  @Test
  void handleRequestShouldRejectNegativeSequence() throws Exception {
    // arrange
    var event = buildEvent("alice", "-7");

    // act
    var response = createBookmarkHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void handleRequestShouldKeepBookmarksIsolatedPerUser() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000L));

    // act
    createBookmarkHandler.handleRequest(buildEvent("alice", "100"), null);
    createBookmarkHandler.handleRequest(buildEvent("bob", "200"), null);

    // assert
    var allItems = bookmarkTable.scan().items().stream().toList();
    assertThat(allItems).hasSize(2);

    var aliceItems = allItems.stream().filter(i -> "alice".equals(i.getUser())).toList();
    assertThat(aliceItems).hasSize(1);
    assertThat(aliceItems.get(0).getSequence()).isEqualTo(100L);

    var bobItems = allItems.stream().filter(i -> "bob".equals(i.getUser())).toList();
    assertThat(bobItems).hasSize(1);
    assertThat(bobItems.get(0).getSequence()).isEqualTo(200L);
  }

  @Test
  void handleRequestShouldNotTouchTermRows() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000L));
    var termItem =
        TermItem.create(1316830L, "新橋", "しんばし", "shinbashi", 18472, 0, "{\"tag\":\"div\"}");
    termTable.putItem(termItem);

    // act
    createBookmarkHandler.handleRequest(buildEvent("alice", "1316830"), null);

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
