package com.jordansimsmith.tcginventory.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.s3.S3Container;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.games.Games;
import com.jordansimsmith.tcginventory.games.Games.Game;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Testcontainers
public class CatalogSnapshotStoreIntegrationTest {
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();
  @Container private static final S3Container s3Container = new S3Container();

  private TcgInventoryTestFactory factory;
  private CatalogSnapshotStore store;
  private CatalogRepository repository;

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
    factory.s3Client().createBucket(request -> request.bucket(CatalogSnapshotStore.BUCKET));
  }

  @BeforeEach
  void setUp() {
    factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());
    DynamoDbUtils.reset(factory.dynamoDbClient());
    var s3Client = factory.s3Client();
    s3Client
        .listObjectsV2(
            request -> request.bucket(CatalogSnapshotStore.BUCKET).prefix("catalogs/tcgcsv/"))
        .contents()
        .forEach(
            object ->
                s3Client.deleteObject(
                    request -> request.bucket(CatalogSnapshotStore.BUCKET).key(object.key())));
    repository = new CatalogRepository(factory.catalogSnapshotTable(), factory.dynamoDbClient());
    store =
        new CatalogSnapshotStore(
            factory.s3Client(),
            CatalogSnapshotStore.BUCKET,
            repository,
            new TcgCsvCatalogArtifactCodec(factory.objectMapper()));
  }

  @Test
  void createSnapshotShouldPublishAndLoadArtifact() throws Exception {
    // arrange
    var snapshot = snapshot("pokemon", "2026-10-07T20:06:09Z", "Pikachu", 25);

    // act
    var item = store.createSnapshot(snapshot);

    // assert
    assertThat(item.getPk()).isEqualTo("CATALOG#pokemon");
    assertThat(item.getSk()).isEqualTo("SNAPSHOT#2026-10-07T20:06:09Z");
    assertThat(item.getS3Key())
        .isEqualTo("catalogs/tcgcsv/pokemon/snapshots/2026-10-07T20:06:09Z.json.gz");
    assertThat(item.getChecksumSha256()).hasSize(64);
    var storedItem =
        factory
            .dynamoDbClient()
            .getItem(
                request ->
                    request
                        .tableName(TcgInventoryTable.TABLE_NAME)
                        .consistentRead(true)
                        .key(
                            Map.of(
                                CatalogSnapshotItem.PK,
                                AttributeValue.builder().s(item.getPk()).build(),
                                CatalogSnapshotItem.SK,
                                AttributeValue.builder().s(item.getSk()).build())))
            .item();
    assertThat(storedItem)
        .containsOnlyKeys(
            "pk",
            "sk",
            "game",
            "snapshot_id",
            "source_updated_at",
            "created_at",
            "s3_key",
            "checksum_sha256");
    assertThat(repository.getSnapshot("pokemon", snapshot.snapshotId())).isNotNull();
    assertThat(store.getSnapshot(item)).isEqualTo(snapshot);
  }

  @Test
  void getLatestSnapshotShouldReturnNullWhenNothingHasBeenPublished() {
    // act
    var latestSnapshot = repository.getLatestSnapshot("pokemon");

    // assert
    assertThat(latestSnapshot).isNull();
  }

  @Test
  void tcgPlayerCatalogShouldLoadPublishedSnapshotFromLocalStorage() throws Exception {
    // arrange
    store.createSnapshot(snapshot("pokemon", "2026-10-07T20:06:09Z", "Pikachu", 25));
    factory.fakeClock().setTime(Instant.parse("2026-10-08T20:06:09Z"));
    var catalog = new TcgPlayerCatalog("pokemon", repository, store, factory.fakeClock());

    // act
    var card = catalog.getCard("25");
    var search = catalog.search("Pikachu", "normal", null);
    var alternatives = catalog.findAlternatives("25", "normal", null);

    // assert
    assertThat(card.game()).isEqualTo("pokemon");
    assertThat(card.name()).isEqualTo("Pikachu");
    assertThat(card.setCode()).isEqualTo("BASE");
    assertThat(card.collectorNumber()).isEqualTo("58");
    assertThat(card.availableFinishes()).containsExactly("normal");
    assertThat(search.cards()).containsExactly(card);
    assertThat(search.nextContinuation()).isNull();
    assertThat(alternatives.cards()).containsExactly(card);
    assertThat(alternatives.nextContinuation()).isNull();
  }

  @Test
  void catalogListHandlersShouldMapConfiguredPokemonPagesAndErrors() throws Exception {
    // arrange
    var page =
        new CatalogPage(
            List.of(
                new CatalogCard(
                    "pokemon",
                    "25",
                    "Pikachu",
                    "BASE",
                    "Base Set",
                    "58",
                    new CatalogCard.ImageUrls("small.jpg", "normal.jpg"),
                    List.of("normal"))),
            "next");
    var catalogs = factory.fakeCardCatalogs();
    catalogs.setSearchPage("pokemon", page);
    catalogs.setAlternativesPage("pokemon", page);
    var searchHandler = new FindCatalogCardsHandler(factory);
    var alternativesHandler = new FindCatalogAlternativesHandler(factory);
    var searchEvent =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", "Basic am9yZGFuOnBhc3N3b3Jk"))
            .withQueryStringParameters(
                Map.of("game", "pokemon", "query", "Pikachu", "finish", "normal"))
            .build();
    var alternativesEvent =
        APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", "Basic am9yZGFuOnBhc3N3b3Jk"))
            .withQueryStringParameters(Map.of("game", "pokemon", "finish", "normal"))
            .withPathParameters(Map.of("external_id", "25"))
            .build();

    // act
    var searchResponse = searchHandler.handleRequest(searchEvent, null);
    var alternativesResponse = alternativesHandler.handleRequest(alternativesEvent, null);

    // assert
    for (var response : List.of(searchResponse, alternativesResponse)) {
      assertThat(response.getStatusCode()).isEqualTo(200);
      assertThat(factory.objectMapper().readValue(response.getBody(), CatalogPage.class))
          .isEqualTo(page);
    }
    var failures =
        Map.of(
            400, new CatalogException.BadRequest("continuation is invalid"),
            404, new CatalogException.NotFound("card not found"),
            503, new CatalogException.Unavailable("storage unavailable"));
    for (var entry : failures.entrySet()) {
      var statusCode = entry.getKey();
      var failure = entry.getValue();
      catalogs.setFailure("pokemon", failure);
      assertThat(searchHandler.handleRequest(searchEvent, null).getStatusCode())
          .isEqualTo(statusCode);
      assertThat(alternativesHandler.handleRequest(alternativesEvent, null).getStatusCode())
          .isEqualTo(statusCode);
    }
  }

  @Test
  void tcgPlayerCatalogShouldIsolateGamesSharingSourceAndProductId() throws Exception {
    // arrange
    var pokemonSnapshot = snapshot("pokemon", "2026-10-07T20:06:09Z", "Pikachu", 25);
    var otherSnapshot = snapshot("other", "2026-10-07T20:06:09Z", "Other game card", 25);
    var pokemonItem = store.createSnapshot(pokemonSnapshot);
    var otherItem = store.createSnapshot(otherSnapshot);
    factory.fakeClock().setTime(Instant.parse("2026-10-08T20:06:09Z"));
    var otherGame =
        new Game("other", "Other game", "tcgplayer", false, false, List.of(), List.of());
    var catalogs =
        new Catalogs(
            Map.of(
                "pokemon",
                new TcgPlayerCatalog("pokemon", repository, store, factory.fakeClock()),
                "other",
                new TcgPlayerCatalog("other", repository, store, factory.fakeClock())));

    // act
    var pokemonCard = catalogs.forGame(Games.POKEMON_ENGLISH).getCard("25");
    var otherCard = catalogs.forGame(otherGame).getCard("25");
    store.createSnapshot(snapshot("pokemon", "2026-10-08T20:06:09Z", "Raichu", 25));
    factory.fakeClock().setTime(Instant.parse("2026-10-08T20:11:09Z"));

    // assert
    assertThat(pokemonItem.getS3Key()).isNotEqualTo(otherItem.getS3Key());
    assertThat(pokemonCard.name()).isEqualTo("Pikachu");
    assertThat(otherCard.name()).isEqualTo("Other game card");
    assertThat(catalogs.forGame(Games.POKEMON_ENGLISH).getCard("25").name()).isEqualTo("Raichu");
    assertThat(catalogs.forGame(otherGame).getCard("25").name()).isEqualTo("Other game card");
  }

  @Test
  void getCatalogCardHandlerShouldMapPokemonLookupStatuses() {
    // arrange
    factory
        .fakeCardCatalogs()
        .addCard(
            new CatalogCard(
                "pokemon",
                "25",
                "Pikachu",
                "BASE",
                "Base Set",
                "58",
                new CatalogCard.ImageUrls("small.jpg", "normal.jpg"),
                List.of("normal")));
    var handler = new GetCatalogCardHandler(factory);

    // act
    var found = handler.handleRequest(catalogEvent("25"), null);
    var missingId = handler.handleRequest(catalogEventWithoutId(), null);
    var absent = handler.handleRequest(catalogEvent("not-numeric"), null);
    var missing = handler.handleRequest(catalogEvent("999"), null);
    factory
        .fakeCardCatalogs()
        .setFailure("pokemon", new CatalogException.Unavailable("storage unavailable"));
    var unavailable = handler.handleRequest(catalogEvent("25"), null);

    // assert
    assertThat(found.getStatusCode()).isEqualTo(200);
    assertThat(missingId.getStatusCode()).isEqualTo(400);
    assertThat(absent.getStatusCode()).isEqualTo(404);
    assertThat(missing.getStatusCode()).isEqualTo(404);
    assertThat(unavailable.getStatusCode()).isEqualTo(503);
  }

  @Test
  void tcgPlayerCatalogShouldReportMissingPublicationAsUnavailable() {
    // arrange
    var catalog = new TcgPlayerCatalog("pokemon", repository, store, factory.fakeClock());

    // act/assert
    assertThatThrownBy(() -> catalog.getCard("25"))
        .isInstanceOf(CatalogException.Unavailable.class)
        .hasMessageContaining("not been published");
  }

  @Test
  void tcgPlayerCatalogShouldReportMissingArtifactAsUnavailable() {
    // arrange
    var snapshotId = "2026-10-07T20:06:09Z";
    repository.createSnapshot(
        CatalogSnapshotItem.create(
            "pokemon",
            snapshotId,
            Instant.parse(snapshotId),
            Instant.parse("2026-10-08T00:00:00Z"),
            "catalogs/missing.json.gz",
            "checksum"));
    var catalog = new TcgPlayerCatalog("pokemon", repository, store, factory.fakeClock());

    // act/assert
    assertThatThrownBy(() -> catalog.getCard("25"))
        .isInstanceOf(CatalogException.Unavailable.class);
  }

  @Test
  void tcgPlayerCatalogShouldReportMalformedArtifactsAsUnavailable() throws Exception {
    // arrange
    var snapshotId = "2026-10-07T20:06:09Z";
    var s3Key = "catalogs/tcgcsv/pokemon/snapshots/%s.json.gz".formatted(snapshotId);
    factory
        .s3Client()
        .putObject(
            request -> request.bucket(CatalogSnapshotStore.BUCKET).key(s3Key),
            RequestBody.fromBytes(gzip("{not json".getBytes(StandardCharsets.UTF_8))));
    repository.createSnapshot(
        CatalogSnapshotItem.create(
            "pokemon",
            snapshotId,
            Instant.parse(snapshotId),
            Instant.parse("2026-10-08T00:00:00Z"),
            s3Key,
            "checksum"));
    var catalog = new TcgPlayerCatalog("pokemon", repository, store, factory.fakeClock());

    // act/assert
    assertThatThrownBy(() -> catalog.getCard("25"))
        .isInstanceOf(CatalogException.Unavailable.class);
  }

  @Test
  void tcgPlayerCatalogShouldReportInvalidGzipAsUnavailable() throws Exception {
    // arrange
    var snapshotId = "2026-10-07T20:06:09Z";
    var s3Key = "catalogs/tcgcsv/pokemon/snapshots/%s.json.gz".formatted(snapshotId);
    factory
        .s3Client()
        .putObject(
            request -> request.bucket(CatalogSnapshotStore.BUCKET).key(s3Key),
            RequestBody.fromBytes(new byte[] {1, 2, 3, 4}));
    repository.createSnapshot(
        CatalogSnapshotItem.create(
            "pokemon",
            snapshotId,
            Instant.parse(snapshotId),
            Instant.parse("2026-10-08T00:00:00Z"),
            s3Key,
            "checksum"));
    var catalog = new TcgPlayerCatalog("pokemon", repository, store, factory.fakeClock());

    // act/assert
    assertThatThrownBy(() -> catalog.getCard("25"))
        .isInstanceOf(CatalogException.Unavailable.class);
  }

  @Test
  void getSnapshotShouldPropagateMissingArtifact() throws Exception {
    // arrange
    var item = store.createSnapshot(snapshot("pokemon", "2026-10-07T20:06:09Z", "Pikachu", 25));
    factory
        .s3Client()
        .deleteObject(request -> request.bucket(CatalogSnapshotStore.BUCKET).key(item.getS3Key()));

    // act/assert
    assertThatThrownBy(() -> store.getSnapshot(item)).isInstanceOf(NoSuchKeyException.class);
  }

  @Test
  void createSnapshotShouldReuseFirstArtifactAndMetadataWhenObjectAlreadyExists() throws Exception {
    // arrange
    var original = snapshot("pokemon", "2026-10-07T20:06:09Z", "Pikachu", 25);
    var candidate = snapshot("pokemon", "2026-10-07T20:06:09Z", "Changed candidate", 26);
    var codec = new TcgCsvCatalogArtifactCodec(factory.objectMapper());
    var originalBytes = codec.encodeGzip(original);
    factory
        .s3Client()
        .putObject(
            request ->
                request
                    .bucket(CatalogSnapshotStore.BUCKET)
                    .key(CatalogSnapshotStore.key("pokemon", original.snapshotId()))
                    .contentType("application/gzip"),
            RequestBody.fromBytes(originalBytes));

    // act
    var item = store.createSnapshot(candidate);

    // assert
    assertThat(item.getCreatedAt()).isEqualTo(Instant.ofEpochSecond(original.createdAt()));
    assertThat(item.getChecksumSha256()).isEqualTo(checksum(originalBytes));
    assertThat(store.getSnapshot(item)).isEqualTo(original);
  }

  @Test
  void createSnapshotShouldLeavePreviousLatestWhenUploadFails() throws Exception {
    // arrange
    var published =
        store.createSnapshot(snapshot("pokemon", "2026-10-07T20:06:09Z", "Pikachu", 25));
    var failingStore =
        new CatalogSnapshotStore(
            factory.s3Client(),
            "missing-catalog-bucket",
            repository,
            new TcgCsvCatalogArtifactCodec(factory.objectMapper()));

    // act/assert
    assertThatThrownBy(
            () ->
                failingStore.createSnapshot(
                    snapshot("pokemon", "2026-10-08T20:06:09Z", "Raichu", 26)))
        .isInstanceOf(RuntimeException.class);
    assertThat(repository.getLatestSnapshot("pokemon").getSnapshotId())
        .isEqualTo(published.getSnapshotId());
  }

  @Test
  void createSnapshotShouldPropagateUploadConflictAndRecoverOnRetry() throws Exception {
    // arrange
    var published =
        store.createSnapshot(snapshot("pokemon", "2026-10-07T20:06:09Z", "Pikachu", 25));
    var conflictingS3Client =
        new S3Client() {
          @Override
          public PutObjectResponse putObject(PutObjectRequest request, RequestBody requestBody) {
            throw S3Exception.builder()
                .statusCode(409)
                .message("conditional upload conflict")
                .build();
          }

          @Override
          public String serviceName() {
            return "s3";
          }

          @Override
          public void close() {}
        };
    var conflictingStore =
        new CatalogSnapshotStore(
            conflictingS3Client,
            CatalogSnapshotStore.BUCKET,
            repository,
            new TcgCsvCatalogArtifactCodec(factory.objectMapper()));
    var candidate = snapshot("pokemon", "2026-10-08T20:06:09Z", "Raichu", 26);

    // act/assert
    assertThatThrownBy(() -> conflictingStore.createSnapshot(candidate))
        .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(409));
    assertThat(repository.getLatestSnapshot("pokemon").getSnapshotId())
        .isEqualTo(published.getSnapshotId());
    assertThat(repository.getSnapshot("pokemon", candidate.snapshotId())).isNull();

    // act
    var retriedItem = store.createSnapshot(candidate);

    // assert
    assertThat(store.getSnapshot(retriedItem)).isEqualTo(candidate);
  }

  @Test
  void createSnapshotShouldLeavePreviousLatestWhenRegistryWriteFailsAndRetry() throws Exception {
    // arrange
    var published =
        store.createSnapshot(snapshot("pokemon", "2026-10-07T20:06:09Z", "Pikachu", 25));
    var failingRepository =
        new CatalogRepository(factory.catalogSnapshotTable(), factory.dynamoDbClient()) {
          @Override
          public CatalogSnapshotItem createSnapshot(CatalogSnapshotItem item) {
            throw new IllegalStateException("registry unavailable");
          }
        };
    var failingStore =
        new CatalogSnapshotStore(
            factory.s3Client(),
            CatalogSnapshotStore.BUCKET,
            failingRepository,
            new TcgCsvCatalogArtifactCodec(factory.objectMapper()));
    var retry = snapshot("pokemon", "2026-10-08T20:06:09Z", "Raichu", 26);

    // act/assert
    assertThatThrownBy(() -> failingStore.createSnapshot(retry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("registry unavailable");
    assertThat(repository.getLatestSnapshot("pokemon").getSnapshotId())
        .isEqualTo(published.getSnapshotId());

    // act
    var retriedItem = store.createSnapshot(retry);

    // assert
    assertThat(store.getSnapshot(retriedItem)).isEqualTo(retry);
  }

  @Test
  void createSnapshotShouldRecoverWhenRegistryWriteAcknowledgementIsLost() throws Exception {
    // arrange
    var lostAcknowledgementRepository =
        new CatalogRepository(factory.catalogSnapshotTable(), factory.dynamoDbClient()) {
          private boolean loseAcknowledgement = true;

          @Override
          public CatalogSnapshotItem createSnapshot(CatalogSnapshotItem item) {
            var created = super.createSnapshot(item);
            if (loseAcknowledgement) {
              loseAcknowledgement = false;
              throw new IllegalStateException("write acknowledgement was lost");
            }
            return created;
          }
        };
    var retryingStore =
        new CatalogSnapshotStore(
            factory.s3Client(),
            CatalogSnapshotStore.BUCKET,
            lostAcknowledgementRepository,
            new TcgCsvCatalogArtifactCodec(factory.objectMapper()));
    var snapshot = snapshot("pokemon", "2026-10-07T20:06:09Z", "Pikachu", 25);

    // act/assert
    assertThatThrownBy(() -> retryingStore.createSnapshot(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("write acknowledgement was lost");

    // act
    var item = retryingStore.createSnapshot(snapshot);

    // assert
    assertThat(item.getSnapshotId()).isEqualTo(snapshot.snapshotId());
    assertThat(repository.getLatestSnapshot("pokemon").getSnapshotId())
        .isEqualTo(snapshot.snapshotId());
  }

  @Test
  void createSnapshotShouldPropagateRegistryConflictAndRecoverOnRetry() throws Exception {
    // arrange
    var snapshot = snapshot("pokemon", "2026-10-07T20:06:09Z", "Pikachu", 25);
    var published = store.createSnapshot(snapshot);
    var repositoryWithStaleRead =
        new CatalogRepository(factory.catalogSnapshotTable(), factory.dynamoDbClient()) {
          private boolean returnStaleRead = true;

          @Override
          public CatalogSnapshotItem getSnapshot(String game, String snapshotId) {
            if (returnStaleRead) {
              returnStaleRead = false;
              return null;
            }
            return super.getSnapshot(game, snapshotId);
          }
        };
    var retryingStore =
        new CatalogSnapshotStore(
            factory.s3Client(),
            CatalogSnapshotStore.BUCKET,
            repositoryWithStaleRead,
            new TcgCsvCatalogArtifactCodec(factory.objectMapper()));

    // act/assert
    assertThatThrownBy(() -> retryingStore.createSnapshot(snapshot))
        .isInstanceOf(ConditionalCheckFailedException.class);

    // act
    var item = retryingStore.createSnapshot(snapshot);

    // assert
    assertThat(item.getSnapshotId()).isEqualTo(published.getSnapshotId());
    assertThat(item.getCreatedAt()).isEqualTo(published.getCreatedAt());
    assertThat(item.getChecksumSha256()).isEqualTo(published.getChecksumSha256());
  }

  @Test
  void latestSnapshotShouldBeOrderedBySourceTimeAndScopedByGame() throws Exception {
    // arrange
    factory
        .dynamoDbClient()
        .putItem(
            PutItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(
                    Map.of(
                        CatalogSnapshotItem.PK,
                        AttributeValue.builder().s("CATALOG#pokemon").build(),
                        CatalogSnapshotItem.SK,
                        AttributeValue.builder().s("REFRESH#in-progress").build()))
                .build());
    var newestPokemon = snapshot("pokemon", "2026-10-08T20:06:09Z", "Pikachu", 25);
    var oldestPokemon = snapshot("pokemon", "2026-10-07T20:06:09Z", "Raichu", 26);
    var sameTimeMtg = snapshot("mtg", "2026-10-08T20:06:09Z", "Lightning Bolt", 27);
    store.createSnapshot(newestPokemon);
    store.createSnapshot(sameTimeMtg);

    // act
    var delayed = store.createSnapshot(oldestPokemon);

    // assert
    assertThat(repository.getLatestSnapshot("pokemon").getSnapshotId())
        .isEqualTo(newestPokemon.snapshotId());
    assertThat(repository.getLatestSnapshot("mtg").getSnapshotId())
        .isEqualTo(sameTimeMtg.snapshotId());
    assertThat(delayed.getSnapshotId()).isEqualTo(oldestPokemon.snapshotId());
  }

  private static TcgCsvCatalogSnapshot snapshot(
      String game, String snapshotId, String productName, int productId) {
    var sourceUpdatedAt = Instant.parse(snapshotId);
    return new TcgCsvCatalogSnapshot(
        1,
        game,
        "tcgplayer",
        "tcgcsv",
        game.equals("pokemon") ? 3 : 1,
        snapshotId,
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxx")
            .withZone(ZoneOffset.UTC)
            .format(sourceUpdatedAt),
        sourceUpdatedAt.getEpochSecond(),
        Instant.parse("2026-10-08T01:00:00Z").getEpochSecond(),
        List.of(new TcgCsvCatalogSnapshot.Group(1, "BASE", "Base Set")),
        List.of(
            new TcgCsvCatalogSnapshot.Product(productId, productName, 1, "58", List.of("normal"))));
  }

  private static String checksum(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static byte[] gzip(byte[] bytes) throws Exception {
    var output = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(output)) {
      gzip.write(bytes);
    }
    return output.toByteArray();
  }

  private static APIGatewayV2HTTPEvent catalogEvent(String externalId) {
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", "Basic am9yZGFuOnBhc3N3b3Jk"))
        .withQueryStringParameters(Map.of("game", "pokemon"))
        .withPathParameters(Map.of("external_id", externalId))
        .build();
  }

  private static APIGatewayV2HTTPEvent catalogEventWithoutId() {
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", "Basic am9yZGFuOnBhc3N3b3Jk"))
        .withQueryStringParameters(Map.of("game", "pokemon"))
        .withPathParameters(Map.of())
        .build();
  }
}
