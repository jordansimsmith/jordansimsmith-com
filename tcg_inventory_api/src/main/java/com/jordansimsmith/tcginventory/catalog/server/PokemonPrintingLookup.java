package com.jordansimsmith.tcginventory.catalog.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.catalog.client.CatalogPrinting;
import com.jordansimsmith.tcginventory.games.client.Finish;
import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.time.Clock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

public class PokemonPrintingLookup {
  private static final Duration REGISTRY_CHECK_INTERVAL = Duration.ofMinutes(5);

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Snapshot(
      @JsonProperty("game") String game,
      @JsonProperty("groups") List<Group> groups,
      @JsonProperty("products") List<Product> products) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Group(
      @JsonProperty("group_id") int id,
      @JsonProperty("set_code") String setCode,
      @JsonProperty("set_name") String setName) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Product(
      @JsonProperty("product_id") int id,
      @JsonProperty("name") String name,
      @JsonProperty("group_id") int groupId,
      @JsonProperty("collector_number") String collectorNumber,
      @JsonProperty("available_finishes") List<String> finishes) {}

  private final DynamoDbClient dynamoDb;
  private final S3Client s3;
  private final String tableName;
  private final String bucket;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private Snapshot snapshot;
  private String snapshotId;
  private Instant lastCheckedAt;

  public PokemonPrintingLookup(
      DynamoDbClient dynamoDb,
      S3Client s3,
      String tableName,
      String bucket,
      ObjectMapper objectMapper,
      Clock clock) {
    this.dynamoDb = dynamoDb;
    this.s3 = s3;
    this.tableName = tableName;
    this.bucket = bucket;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  public List<CatalogPrinting> findCards(Game game, Set<String> externalIds)
      throws ServiceUnavailableException {
    try {
      return doFindCards(game, externalIds);
    } catch (IOException | SdkException e) {
      throw new ServiceUnavailableException("Pokémon catalog is temporarily unavailable", e);
    }
  }

  private List<CatalogPrinting> doFindCards(Game game, Set<String> externalIds)
      throws IOException, ServiceUnavailableException {
    var currentSnapshot = loadSnapshot(game.id());
    var groupsById = new HashMap<Integer, Group>();
    for (var group : currentSnapshot.groups()) {
      if (group == null || groupsById.putIfAbsent(group.id(), group) != null) {
        throw new ServiceUnavailableException("catalog returned inconsistent group data");
      }
    }

    var matchedIds = new HashSet<String>();
    var cards = new ArrayList<CatalogPrinting>();
    for (var product : currentSnapshot.products()) {
      if (product == null) {
        throw new ServiceUnavailableException("catalog returned invalid product data");
      }
      var id = Integer.toString(product.id());
      if (!externalIds.contains(id)) {
        continue;
      }
      var group = groupsById.get(product.groupId());
      if (!matchedIds.add(id)
          || group == null
          || group.setCode() == null
          || group.setName() == null
          || product.name() == null
          || product.collectorNumber() == null
          || product.finishes() == null) {
        throw new ServiceUnavailableException("catalog returned inconsistent product data");
      }
      var finishes =
          game.finishes().stream().map(Finish::id).filter(product.finishes()::contains).toList();
      if (!finishes.isEmpty()) {
        cards.add(
            new CatalogPrinting(
                game.id(),
                id,
                product.name(),
                group.setCode(),
                group.setName(),
                product.collectorNumber(),
                finishes));
      }
    }
    return List.copyOf(cards);
  }

  private synchronized Snapshot loadSnapshot(String game)
      throws IOException, ServiceUnavailableException {
    if (snapshot != null && clock.now().isBefore(lastCheckedAt.plus(REGISTRY_CHECK_INTERVAL))) {
      return snapshot;
    }

    var latest =
        dynamoDb.query(
            request ->
                request
                    .tableName(tableName)
                    .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                    .expressionAttributeValues(
                        Map.of(
                            ":pk", AttributeValue.fromS("CATALOG#" + game),
                            ":prefix", AttributeValue.fromS("SNAPSHOT#")))
                    .scanIndexForward(false)
                    .limit(1)
                    .consistentRead(true));
    if (latest.items().isEmpty()) {
      throw new ServiceUnavailableException("catalog has not been published for game: " + game);
    }

    var entry = latest.items().getFirst();
    var latestId = requiredAttribute(entry, "snapshot_id");
    if (!latestId.equals(snapshotId)) {
      var key = requiredAttribute(entry, "s3_key");
      var request = GetObjectRequest.builder().bucket(bucket).key(key).build();
      try (var object = s3.getObject(request);
          var gzip = new GZIPInputStream(object)) {
        var loaded = objectMapper.readValue(gzip, Snapshot.class);
        if (loaded == null
            || !game.equals(loaded.game())
            || loaded.groups() == null
            || loaded.products() == null) {
          throw new ServiceUnavailableException("catalog returned invalid snapshot data");
        }
        snapshot = loaded;
        snapshotId = latestId;
      }
    }
    lastCheckedAt = clock.now();
    return snapshot;
  }

  private String requiredAttribute(Map<String, AttributeValue> entry, String name)
      throws ServiceUnavailableException {
    var value = entry.get(name);
    if (value == null || value.s() == null || value.s().isBlank()) {
      throw new ServiceUnavailableException("catalog registry entry is missing " + name);
    }
    return value.s();
  }
}
