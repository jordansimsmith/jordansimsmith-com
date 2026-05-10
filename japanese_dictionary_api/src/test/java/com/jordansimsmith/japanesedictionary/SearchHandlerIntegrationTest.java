package com.jordansimsmith.japanesedictionary;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Testcontainers
public class SearchHandlerIntegrationTest {
  private ObjectMapper objectMapper;
  private DynamoDbTable<JapaneseDictionaryItem> japaneseDictionaryTable;

  private SearchHandler searchHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = JapaneseDictionaryTestFactory.create(dynamoDbContainer.getEndpoint());
    var table = factory.japaneseDictionaryTable();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory = JapaneseDictionaryTestFactory.create(dynamoDbContainer.getEndpoint());

    objectMapper = factory.objectMapper();
    japaneseDictionaryTable = factory.japaneseDictionaryTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    searchHandler = new SearchHandler(factory);

    seedFixtures();
  }

  private void seedFixtures() {
    putItem(1L, "新", "しん", "shin", null, 0, "{\"tag\":\"div\",\"content\":\"new\"}");
    putItem(2L, "新橋", "しんばし", "shinbashi", 18472, 0, "{\"tag\":\"div\",\"content\":\"Shinbashi\"}");
    putItem(3L, "新しい", "あたらしい", "atarashii", 200, 3, "{\"tag\":\"div\",\"content\":\"new (adj)\"}");
    putItem(4L, "しんぱい", "しんぱい", "shinpai", 5000, 0, "{\"tag\":\"div\",\"content\":\"worry\"}");
    putItem(
        5L,
        "心",
        "しん",
        "shin",
        null,
        1,
        "{\"tag\":\"img\",\"path\":\"jitendex/graphics/heart.png\",\"description\":\"heart\"}");
    putItem(6L, "新規", "しんき", "shinki", 15000, null, "{\"tag\":\"div\",\"content\":\"new entry\"}");
    putItem(7L, "新聞", "しんぶん", "shinbun", 1000, null, "{\"tag\":\"div\",\"content\":\"newspaper\"}");
    putItem(8L, "信用", "しんよう", "shinyou", 8000, 2, "{\"tag\":\"div\",\"content\":\"credit\"}");
    putItem(9L, "親切", "しんせつ", "shinsetsu", 4000, 1, "{\"tag\":\"div\",\"content\":\"kind\"}");
    putItem(10L, "信号", "しんごう", "shingou", 3000, null, "{\"tag\":\"div\",\"content\":\"signal\"}");
    putItem(11L, "新人", "しんじん", "shinjin", 12000, 0, "{\"tag\":\"div\",\"content\":\"newcomer\"}");
    putItem(12L, "進行", "しんこう", "shinkou", 2000, 0, "{\"tag\":\"div\",\"content\":\"progress\"}");
    putItem(13L, "紳士", "しんし", "shinshi", 7000, 1, "{\"tag\":\"div\",\"content\":\"gentleman\"}");
  }

  private void putItem(
      long sequence,
      String expression,
      String reading,
      String readingRomaji,
      @Nullable Integer frequencyRank,
      @Nullable Integer pitch,
      String glossaryRaw) {
    var item =
        JapaneseDictionaryItem.create(
            sequence, expression, reading, readingRomaji, frequencyRank, pitch, glossaryRaw);
    japaneseDictionaryTable.putItem(item);
  }

  @Test
  void handleRequestShouldShortCircuitForEmptyQ() throws Exception {
    var event = APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", "")).build();

    var response = searchHandler.handleRequest(event, null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);
    assertThat(body.results()).isEmpty();
  }

  @Test
  void handleRequestShouldShortCircuitWhenQueryStringIsAbsent() throws Exception {
    var event = APIGatewayV2HTTPEvent.builder().build();

    var response = searchHandler.handleRequest(event, null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);
    assertThat(body.results()).isEmpty();
  }

  @Test
  void handleRequestShouldShortCircuitWhenQIsOnlyWhitespace() throws Exception {
    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", "   ")).build();

    var response = searchHandler.handleRequest(event, null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);
    assertThat(body.results()).isEmpty();
  }

  @Test
  void handleRequestShouldRejectQTooLong() throws Exception {
    var event =
        APIGatewayV2HTTPEvent.builder()
            .withQueryStringParameters(Map.of("q", "x".repeat(65)))
            .build();

    var response = searchHandler.handleRequest(event, null);

    assertThat(response.getStatusCode()).isEqualTo(400);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.ErrorResponse.class);
    assertThat(body.message()).isEqualTo("q too long");
  }

  @Test
  void handleRequestShouldTrimBeforeLengthCheck() throws Exception {
    var padded = "  " + "x".repeat(65) + "  ";
    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", padded)).build();

    var response = searchHandler.handleRequest(event, null);

    assertThat(response.getStatusCode()).isEqualTo(400);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.ErrorResponse.class);
    assertThat(body.message()).isEqualTo("q too long");
  }

  @Test
  void handleRequestShouldFindKanjiPrefixOnGsi1() throws Exception {
    var event = APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", "新")).build();

    var response = searchHandler.handleRequest(event, null);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);

    assertThat(body.results())
        .extracting(SearchHandler.SearchResult::sequence)
        .containsExactly(3L, 7L, 11L, 6L, 2L, 1L);
  }

  @Test
  void handleRequestShouldFindKanaPrefixOnGsi2() throws Exception {
    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", "しん")).build();

    var response = searchHandler.handleRequest(event, null);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);

    assertThat(body.results())
        .extracting(SearchHandler.SearchResult::sequence)
        .containsExactly(7L, 12L, 10L, 9L, 4L, 13L, 8L, 11L, 6L, 2L);
  }

  @Test
  void handleRequestShouldFindRomajiPrefixOnGsi3() throws Exception {
    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", "shin")).build();

    var response = searchHandler.handleRequest(event, null);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);

    assertThat(body.results())
        .extracting(SearchHandler.SearchResult::sequence)
        .containsExactly(7L, 12L, 10L, 9L, 4L, 13L, 8L, 11L, 6L, 2L);
  }

  @Test
  void handleRequestShouldNormaliseKunreiRomajiBeforeMatchingGsi3() throws Exception {
    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", "sin")).build();

    var response = searchHandler.handleRequest(event, null);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);

    assertThat(body.results())
        .extracting(SearchHandler.SearchResult::sequence)
        .containsExactly(7L, 12L, 10L, 9L, 4L, 13L, 8L, 11L, 6L, 2L);
  }

  @Test
  void handleRequestShouldOrderByFrequencyAscWithNullsLast() throws Exception {
    var event = APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", "新")).build();

    var response = searchHandler.handleRequest(event, null);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);

    var ranks = body.results().stream().map(SearchHandler.SearchResult::frequencyRank).toList();
    assertThat(ranks).containsExactly(200, 1000, 12000, 15000, 18472, null);
  }

  @Test
  void handleRequestShouldCapAt10Results() throws Exception {
    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", "しん")).build();

    var response = searchHandler.handleRequest(event, null);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);

    assertThat(body.results()).hasSize(10);
  }

  @Test
  void handleRequestShouldDedupTermsReachableViaMultipleGsis() throws Exception {
    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", "しんぱ")).build();

    var response = searchHandler.handleRequest(event, null);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);

    assertThat(body.results()).extracting(SearchHandler.SearchResult::sequence).containsExactly(4L);
  }

  @Test
  void handleRequestShouldHydrateFullRecordIncludingGlossaryTree() throws Exception {
    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", "新橋")).build();

    var response = searchHandler.handleRequest(event, null);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);

    assertThat(body.results()).hasSize(1);
    var result = body.results().get(0);
    assertThat(result.sequence()).isEqualTo(2L);
    assertThat(result.expression()).isEqualTo("新橋");
    assertThat(result.reading()).isEqualTo("しんばし");
    assertThat(result.readingRomaji()).isEqualTo("shinbashi");
    assertThat(result.frequencyRank()).isEqualTo(18472);
    assertThat(result.pitch()).isZero();
    assertThat(result.glossaryRaw().get("tag").asText()).isEqualTo("div");
    assertThat(result.glossaryRaw().get("content").asText()).isEqualTo("Shinbashi");
  }

  @Test
  void handleRequestShouldPassThroughImageGlossaryUntouched() throws Exception {
    var event = APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", "心")).build();

    var response = searchHandler.handleRequest(event, null);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);

    assertThat(body.results()).hasSize(1);
    var result = body.results().get(0);
    assertThat(result.sequence()).isEqualTo(5L);
    assertThat(result.glossaryRaw().get("tag").asText()).isEqualTo("img");
    assertThat(result.glossaryRaw().get("path").asText()).isEqualTo("jitendex/graphics/heart.png");
  }

  @Test
  void handleRequestShouldReturnEmptyForUnmatchedPrefix() throws Exception {
    var event = APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", "猫")).build();

    var response = searchHandler.handleRequest(event, null);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);

    assertThat(body.results()).isEmpty();
  }
}
