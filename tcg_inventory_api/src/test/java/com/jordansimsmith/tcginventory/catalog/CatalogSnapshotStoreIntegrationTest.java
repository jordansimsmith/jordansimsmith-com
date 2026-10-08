package com.jordansimsmith.tcginventory.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.s3.S3Container;
import com.jordansimsmith.tcginventory.Photos;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

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
    factory.s3Client().createBucket(request -> request.bucket(Photos.BUCKET));
  }

  @BeforeEach
  void setUp() {
    factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());
    DynamoDbUtils.reset(factory.dynamoDbClient());
    var s3Client = factory.s3Client();
    s3Client
        .listObjectsV2(request -> request.bucket(Photos.BUCKET).prefix("catalogs/tcgcsv/"))
        .contents()
        .forEach(
            object ->
                s3Client.deleteObject(request -> request.bucket(Photos.BUCKET).key(object.key())));
    repository = new CatalogRepository(factory.catalogSnapshotTable(), factory.dynamoDbClient());
    store =
        new CatalogSnapshotStore(
            factory.s3Client(),
            Photos.BUCKET,
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
    assertThat(item.getGroupCount()).isEqualTo(1);
    assertThat(item.getProductCount()).isEqualTo(1);
    assertThat(item.getArtifactSizeBytes()).isPositive();
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
  void getSnapshotShouldPropagateMissingArtifact() throws Exception {
    // arrange
    var item = store.createSnapshot(snapshot("pokemon", "2026-10-07T20:06:09Z", "Pikachu", 25));
    factory.s3Client().deleteObject(request -> request.bucket(Photos.BUCKET).key(item.getS3Key()));

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
                    .bucket(Photos.BUCKET)
                    .key(CatalogSnapshotStore.key("pokemon", original.snapshotId()))
                    .contentType("application/gzip"),
            RequestBody.fromBytes(originalBytes));

    // act
    var item = store.createSnapshot(candidate);

    // assert
    assertThat(item.getCreatedAt()).isEqualTo(Instant.ofEpochSecond(original.createdAt()));
    assertThat(item.getChecksumSha256()).isEqualTo(checksum(originalBytes));
    assertThat(item.getArtifactSizeBytes()).isEqualTo((long) originalBytes.length);
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
            Photos.BUCKET,
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
            Photos.BUCKET,
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
}
