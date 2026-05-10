package com.jordansimsmith.japanesedictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

public class SearchHandlerTest {
  private ObjectMapper objectMapper;
  private DictionaryFactory factory;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    objectMapper = new ObjectMapper();

    factory = mock(DictionaryFactory.class);
    when(factory.objectMapper()).thenReturn(objectMapper);
    when(factory.httpResponseFactory())
        .thenReturn(new HttpResponseFactory.Builder(objectMapper).build());
    when(factory.japaneseDictionaryTable())
        .thenReturn((DynamoDbTable<JapaneseDictionaryItem>) mock(DynamoDbTable.class));
    when(factory.dynamoDbEnhancedClient()).thenReturn(mock(DynamoDbEnhancedClient.class));
  }

  @Test
  void rankTopNShouldOrderByFrequencyAscWithNullsLast() {
    var items =
        List.of(candidate(1L, 100), candidate(2L, null), candidate(3L, 50), candidate(4L, 75));

    var ranked = SearchHandler.rankTopN(items, 10);

    assertThat(ranked).containsExactly(3L, 4L, 1L, 2L);
  }

  @Test
  void rankTopNShouldBreakTiesBySequenceAsc() {
    var items =
        List.of(candidate(7L, 100), candidate(2L, 100), candidate(5L, 100), candidate(11L, 100));

    var ranked = SearchHandler.rankTopN(items, 10);

    assertThat(ranked).containsExactly(2L, 5L, 7L, 11L);
  }

  @Test
  void rankTopNShouldDedupBySequence() {
    var items =
        List.of(
            candidate(1L, 100),
            candidate(2L, 50),
            candidate(1L, 100),
            candidate(3L, 75),
            candidate(2L, 50));

    var ranked = SearchHandler.rankTopN(items, 10);

    assertThat(ranked).containsExactly(2L, 3L, 1L);
  }

  @Test
  void rankTopNShouldTruncateAtLimit() {
    var items = new ArrayList<JapaneseDictionaryItem>();
    for (var i = 0; i < 25; i++) {
      items.add(candidate(i, i));
    }

    var ranked = SearchHandler.rankTopN(items, 10);

    assertThat(ranked).hasSize(10);
    assertThat(ranked).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
  }

  @Test
  void rankTopNShouldHandleEmptyInput() {
    assertThat(SearchHandler.rankTopN(List.of(), 10)).isEmpty();
  }

  @Test
  void handleRequestShouldReturnEmptyResultsForEmptyQ() throws Exception {
    var handler = new SearchHandler(factory);
    var event = APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", "")).build();

    var response = handler.handleRequest(event, null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);
    assertThat(body.results()).isEmpty();
  }

  @Test
  void handleRequestShouldReturnEmptyResultsWhenQueryStringIsAbsent() throws Exception {
    var handler = new SearchHandler(factory);
    var event = APIGatewayV2HTTPEvent.builder().build();

    var response = handler.handleRequest(event, null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);
    assertThat(body.results()).isEmpty();
  }

  @Test
  void handleRequestShouldShortCircuitWhenQIsOnlyWhitespace() throws Exception {
    var handler = new SearchHandler(factory);
    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", "   ")).build();

    var response = handler.handleRequest(event, null);

    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.SearchResponse.class);
    assertThat(body.results()).isEmpty();
  }

  @Test
  void handleRequestShouldRejectQLongerThanLimit() throws Exception {
    var handler = new SearchHandler(factory);
    var tooLong = "x".repeat(SearchHandler.MAX_QUERY_LENGTH + 1);
    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", tooLong)).build();

    var response = handler.handleRequest(event, null);

    assertThat(response.getStatusCode()).isEqualTo(400);
    var body = objectMapper.readValue(response.getBody(), SearchHandler.ErrorResponse.class);
    assertThat(body.message()).isEqualTo("q too long");
  }

  @Test
  void handleRequestShouldNfcNormaliseAndTrimBeforeLengthCheck() throws Exception {
    var handler = new SearchHandler(factory);
    var padded = "  " + "x".repeat(SearchHandler.MAX_QUERY_LENGTH + 1) + "  ";
    var event =
        APIGatewayV2HTTPEvent.builder().withQueryStringParameters(Map.of("q", padded)).build();

    var response = handler.handleRequest(event, null);

    assertThat(response.getStatusCode()).isEqualTo(400);
  }

  private JapaneseDictionaryItem candidate(long sequence, Integer frequencyRank) {
    var item = new JapaneseDictionaryItem();
    item.setSequence(sequence);
    item.setFrequencyRank(frequencyRank);
    return item;
  }
}
