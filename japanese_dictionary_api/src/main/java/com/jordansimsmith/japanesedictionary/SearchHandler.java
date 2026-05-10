package com.jordansimsmith.japanesedictionary;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;

public class SearchHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(SearchHandler.class);

  private static final int MAX_QUERY_LENGTH = 64;
  private static final int RESULT_LIMIT = 10;

  @VisibleForTesting
  record SearchResult(
      @JsonProperty("sequence") long sequence,
      @JsonProperty("expression") String expression,
      @JsonProperty("reading") String reading,
      @JsonProperty("reading_romaji") String readingRomaji,
      @Nullable @JsonProperty("frequency_rank") Integer frequencyRank,
      @Nullable @JsonProperty("pitch") Integer pitch,
      @JsonProperty("glossary_raw") JsonNode glossaryRaw) {}

  @VisibleForTesting
  record SearchResponse(@JsonProperty("results") List<SearchResult> results) {}

  @VisibleForTesting
  record ErrorResponse(@JsonProperty("message") String message) {}

  private final ObjectMapper objectMapper;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<JapaneseDictionaryItem> japaneseDictionaryTable;
  private final DynamoDbEnhancedClient dynamoDbEnhancedClient;
  private final RomajiNormaliser romajiNormaliser;

  public SearchHandler() {
    this(JapaneseDictionaryFactory.create());
  }

  @VisibleForTesting
  SearchHandler(JapaneseDictionaryFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.japaneseDictionaryTable = factory.japaneseDictionaryTable();
    this.dynamoDbEnhancedClient = factory.dynamoDbEnhancedClient();
    this.romajiNormaliser = factory.romajiNormaliser();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event, context);
    } catch (Exception e) {
      LOGGER.error("error processing search request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event, Context context)
      throws Exception {
    var raw =
        event.getQueryStringParameters() == null ? null : event.getQueryStringParameters().get("q");
    var q = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFC).trim();

    if (q.length() > MAX_QUERY_LENGTH) {
      return httpResponseFactory.badRequest(new ErrorResponse("q too long"));
    }

    if (q.isEmpty()) {
      return httpResponseFactory.ok(new SearchResponse(List.of()));
    }

    var qRomaji = romajiNormaliser.normalise(q);

    var candidates = runParallelQueries(q, qRomaji);
    var topSequences = rankTopN(candidates, RESULT_LIMIT);

    if (topSequences.isEmpty()) {
      return httpResponseFactory.ok(new SearchResponse(List.of()));
    }

    var hydrated = batchGet(topSequences);
    var ordered = orderHydrated(hydrated, topSequences);

    var results = new ArrayList<SearchResult>(ordered.size());
    for (var item : ordered) {
      results.add(toSearchResult(item));
    }

    return httpResponseFactory.ok(new SearchResponse(results));
  }

  private List<JapaneseDictionaryItem> runParallelQueries(String q, String qRomaji) {
    var f1 =
        CompletableFuture.supplyAsync(
            () ->
                queryIndex(
                    JapaneseDictionaryItem.GSI1_NAME, JapaneseDictionaryItem.formatGsi1pk(), q));
    var f2 =
        CompletableFuture.supplyAsync(
            () ->
                queryIndex(
                    JapaneseDictionaryItem.GSI2_NAME, JapaneseDictionaryItem.formatGsi2pk(), q));
    var f3 =
        CompletableFuture.supplyAsync(
            () ->
                queryIndex(
                    JapaneseDictionaryItem.GSI3_NAME,
                    JapaneseDictionaryItem.formatGsi3pk(),
                    qRomaji));

    CompletableFuture.allOf(f1, f2, f3).join();

    var combined = new ArrayList<JapaneseDictionaryItem>();
    combined.addAll(f1.join());
    combined.addAll(f2.join());
    combined.addAll(f3.join());
    return combined;
  }

  private List<JapaneseDictionaryItem> queryIndex(
      String indexName, String partitionValue, String sortPrefix) {
    DynamoDbIndex<JapaneseDictionaryItem> index = japaneseDictionaryTable.index(indexName);
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder().partitionValue(partitionValue).sortValue(sortPrefix).build()))
            .build();
    var items = new ArrayList<JapaneseDictionaryItem>();
    for (var page : index.query(request)) {
      items.addAll(page.items());
    }
    return items;
  }

  private static List<Long> rankTopN(List<JapaneseDictionaryItem> candidates, int limit) {
    var dedup = new LinkedHashMap<Long, JapaneseDictionaryItem>();
    for (var item : candidates) {
      dedup.putIfAbsent(item.getSequence(), item);
    }

    var ranked = new ArrayList<>(dedup.values());
    ranked.sort(
        Comparator.<JapaneseDictionaryItem, Integer>comparing(
                JapaneseDictionaryItem::getFrequencyRank,
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(JapaneseDictionaryItem::getSequence));

    var top = new ArrayList<Long>(Math.min(limit, ranked.size()));
    for (var i = 0; i < ranked.size() && i < limit; i++) {
      top.add(ranked.get(i).getSequence());
    }
    return top;
  }

  private List<JapaneseDictionaryItem> batchGet(List<Long> sequences) {
    var readBatch =
        ReadBatch.builder(JapaneseDictionaryItem.class)
            .mappedTableResource(japaneseDictionaryTable);
    for (var seq : sequences) {
      readBatch.addGetItem(
          Key.builder()
              .partitionValue(JapaneseDictionaryItem.formatPk(seq))
              .sortValue(JapaneseDictionaryItem.formatSk(seq))
              .build());
    }

    var request = BatchGetItemEnhancedRequest.builder().readBatches(readBatch.build()).build();

    var items = new ArrayList<JapaneseDictionaryItem>(sequences.size());
    for (var page : dynamoDbEnhancedClient.batchGetItem(request)) {
      page.resultsForTable(japaneseDictionaryTable).forEach(items::add);
    }
    return items;
  }

  private static List<JapaneseDictionaryItem> orderHydrated(
      List<JapaneseDictionaryItem> hydrated, List<Long> orderedSequences) {
    var bySequence = new HashMap<Long, JapaneseDictionaryItem>();
    for (var item : hydrated) {
      bySequence.put(item.getSequence(), item);
    }

    var ordered = new ArrayList<JapaneseDictionaryItem>(orderedSequences.size());
    var seen = new HashSet<Long>();
    for (var seq : orderedSequences) {
      var item = bySequence.get(seq);
      if (item != null && seen.add(seq)) {
        ordered.add(item);
      }
    }
    return Collections.unmodifiableList(ordered);
  }

  private SearchResult toSearchResult(JapaneseDictionaryItem item) throws Exception {
    var raw = item.getGlossaryRaw();
    var glossaryNode = raw == null ? objectMapper.nullNode() : objectMapper.readTree(raw);
    return new SearchResult(
        item.getSequence(),
        item.getExpression(),
        item.getReading(),
        item.getReadingRomaji(),
        item.getFrequencyRank(),
        item.getPitch(),
        glossaryNode);
  }
}
