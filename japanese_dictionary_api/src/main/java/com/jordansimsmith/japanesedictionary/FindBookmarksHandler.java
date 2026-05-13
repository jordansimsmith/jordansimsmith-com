package com.jordansimsmith.japanesedictionary;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;

public class FindBookmarksHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(FindBookmarksHandler.class);

  private static final String INCLUDE_TERM = "term";
  private static final int BATCH_GET_CHUNK = 100;

  @VisibleForTesting
  record Bookmark(
      @JsonProperty("sequence") long sequence,
      @JsonProperty("created_at") long createdAt,
      @Nullable @JsonProperty("expression") String expression,
      @Nullable @JsonProperty("reading") String reading,
      @Nullable @JsonProperty("reading_romaji") String readingRomaji,
      @Nullable @JsonProperty("frequency_rank") Integer frequencyRank,
      @Nullable @JsonProperty("pitch") Integer pitch,
      @Nullable @JsonProperty("glossary_raw") JsonNode glossaryRaw) {}

  @VisibleForTesting
  record FindBookmarksResponse(@JsonProperty("bookmarks") List<Bookmark> bookmarks) {}

  @VisibleForTesting
  record ErrorResponse(@JsonProperty("message") String message) {}

  private final ObjectMapper objectMapper;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<BookmarkItem> bookmarkTable;
  private final DynamoDbTable<TermItem> termTable;
  private final DynamoDbEnhancedClient dynamoDbEnhancedClient;

  public FindBookmarksHandler() {
    this(JapaneseDictionaryFactory.create());
  }

  @VisibleForTesting
  FindBookmarksHandler(JapaneseDictionaryFactory factory) {
    this.objectMapper = factory.objectMapper();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.bookmarkTable = factory.bookmarkTable();
    this.termTable = factory.termTable();
    this.dynamoDbEnhancedClient = factory.dynamoDbEnhancedClient();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing find bookmarks request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) throws Exception {
    var include =
        event.getQueryStringParameters() == null
            ? null
            : event.getQueryStringParameters().get("include");
    if (include != null && !include.equals(INCLUDE_TERM)) {
      return httpResponseFactory.badRequest(
          new ErrorResponse("include parameter must be 'term' or omitted"));
    }

    var user = requestContextFactory.createCtx(event).user();
    var bookmarkItems = findUserBookmarks(user);

    var bookmarks = new ArrayList<Bookmark>(bookmarkItems.size());
    if (include == null) {
      for (var item : bookmarkItems) {
        bookmarks.add(
            new Bookmark(
                item.getSequence(),
                item.getCreatedAt().getEpochSecond(),
                null,
                null,
                null,
                null,
                null,
                null));
      }
    } else {
      var sequences = bookmarkItems.stream().map(BookmarkItem::getSequence).toList();
      var terms = batchGetTerms(sequences);
      for (var item : bookmarkItems) {
        var term = terms.get(item.getSequence());
        if (term == null) {
          continue;
        }
        var raw = term.getGlossaryRaw();
        bookmarks.add(
            new Bookmark(
                item.getSequence(),
                item.getCreatedAt().getEpochSecond(),
                term.getExpression(),
                term.getReading(),
                term.getReadingRomaji(),
                term.getFrequencyRank(),
                term.getPitch(),
                raw == null ? objectMapper.nullNode() : objectMapper.readTree(raw)));
      }
    }

    return httpResponseFactory.ok(new FindBookmarksResponse(bookmarks));
  }

  private List<BookmarkItem> findUserBookmarks(String user) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(BookmarkItem.formatPk(user))
                        .sortValue(BookmarkItem.BOOKMARK_PREFIX)
                        .build()))
            .build();
    return bookmarkTable.query(request).stream()
        .flatMap(page -> page.items().stream())
        .sorted(
            Comparator.comparing(BookmarkItem::getCreatedAt)
                .reversed()
                .thenComparing(BookmarkItem::getSequence))
        .toList();
  }

  private Map<Long, TermItem> batchGetTerms(List<Long> sequences) {
    var byKey = new HashMap<Long, TermItem>(sequences.size());
    for (var chunk : Lists.partition(sequences, BATCH_GET_CHUNK)) {
      var readBatch = ReadBatch.builder(TermItem.class).mappedTableResource(termTable);
      for (var seq : chunk) {
        readBatch.addGetItem(
            Key.builder()
                .partitionValue(TermItem.formatPk(seq))
                .sortValue(TermItem.formatSk(seq))
                .build());
      }
      var request = BatchGetItemEnhancedRequest.builder().readBatches(readBatch.build()).build();
      for (var page : dynamoDbEnhancedClient.batchGetItem(request)) {
        for (var term : page.resultsForTable(termTable)) {
          byKey.put(term.getSequence(), term);
        }
      }
    }
    return byKey;
  }
}
