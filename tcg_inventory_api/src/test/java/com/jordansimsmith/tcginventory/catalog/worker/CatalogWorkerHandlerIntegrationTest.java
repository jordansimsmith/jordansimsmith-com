package com.jordansimsmith.tcginventory.catalog.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.s3.S3Container;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.catalog.client.GetCardRequest;
import com.jordansimsmith.tcginventory.catalog.server.CatalogRefreshMessage;
import com.jordansimsmith.tcginventory.catalog.server.CatalogRefreshMessageHandler;
import com.jordansimsmith.tcginventory.catalog.server.CatalogRepository;
import com.jordansimsmith.tcginventory.catalog.server.CatalogSnapshotItem;
import com.jordansimsmith.tcginventory.catalog.server.CatalogSnapshotStore;
import com.jordansimsmith.tcginventory.catalog.server.GetCardService;
import com.jordansimsmith.tcginventory.catalog.server.PokemonPrintingLookup;
import com.jordansimsmith.tcginventory.catalog.server.TcgCsvCatalogArtifactCodec;
import com.jordansimsmith.tcginventory.catalog.server.TcgCsvCatalogDefinition;
import com.jordansimsmith.tcginventory.catalog.server.TcgCsvCatalogDefinition.FinishMapping;
import com.jordansimsmith.tcginventory.catalog.server.TcgCsvCatalogNormalizer;
import com.jordansimsmith.tcginventory.catalog.server.TcgCsvCatalogSnapshot;
import com.jordansimsmith.tcginventory.catalog.server.TcgCsvClient;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

@Testcontainers
public class CatalogWorkerHandlerIntegrationTest {
  private static final String BUCKET = "api.tcg-inventory.jordansimsmith.com";
  private static final String REFRESH_BODY =
      """
      {"game":"pokemon","scheduled_at":"2026-10-08T21:00:00Z"}
      """;
  private static final String JAPANESE_REFRESH_BODY =
      """
      {"game":"pokemon_jp","scheduled_at":"2026-10-08T21:30:00Z"}
      """;
  private static final TcgCsvClient.UpdateMarker CURRENT_MARKER =
      new TcgCsvClient.UpdateMarker(
          "2026-10-07T20:06:09+0000", Instant.parse("2026-10-07T20:06:09Z"));
  private static final TcgCsvClient.UpdateMarker NEXT_MARKER =
      new TcgCsvClient.UpdateMarker(
          "2026-10-08T20:06:09+0000", Instant.parse("2026-10-08T20:06:09Z"));

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();
  @Container private static final S3Container s3Container = new S3Container();

  private TcgInventoryTestFactory factory;
  private CatalogRepository repository;
  private CatalogSnapshotStore store;
  private FakeTcgCsvClient tcgCsvClient;
  private CatalogWorkerHandler handler;

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
    factory.s3Client().createBucket(request -> request.bucket(BUCKET));
  }

  @BeforeEach
  void setUp() {
    factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());
    DynamoDbUtils.reset(factory.dynamoDbClient());
    var s3Client = factory.s3Client();
    s3Client
        .listObjectsV2(request -> request.bucket(BUCKET).prefix("catalogs/tcgcsv/"))
        .contents()
        .forEach(
            object -> s3Client.deleteObject(request -> request.bucket(BUCKET).key(object.key())));
    var table =
        factory
            .dynamoDbEnhancedClient()
            .table(
                factory.tableDefinition().tableName(),
                TableSchema.fromBean(CatalogSnapshotItem.class));
    repository = spy(new CatalogRepository(table, factory.dynamoDbClient()));
    store =
        new CatalogSnapshotStore(
            factory.s3Client(),
            BUCKET,
            repository,
            new TcgCsvCatalogArtifactCodec(factory.objectMapper()));
    tcgCsvClient = spy(new FakeTcgCsvClient());
    handler =
        new CatalogWorkerHandler(
            factory.objectMapper(),
            new CatalogRefreshMessageHandler(
                tcgCsvClient, new TcgCsvCatalogNormalizer(), store, repository, factory.clock()));
  }

  @Test
  void handleRequestShouldPublishSnapshotWithoutSetCodeReadableByCatalog() throws Exception {
    // arrange
    doReturn(List.of(new TcgCsvClient.Group(3170, 3, "Silver Tempest", "")))
        .when(tcgCsvClient)
        .findGroups(3);

    // act
    handler.handleRequest(event(REFRESH_BODY), null);

    // assert
    var latest = repository.getLatestSnapshot("pokemon");
    assertThat(latest).isNotNull();
    var snapshot = store.getSnapshot(latest);
    assertThat(snapshot.sourceMarker()).isEqualTo(CURRENT_MARKER.sourceMarker());
    assertThat(snapshot.groups())
        .containsExactly(new TcgCsvCatalogSnapshot.Group(3170, null, "Silver Tempest"));
    assertThat(snapshot.products())
        .containsExactly(
            new TcgCsvCatalogSnapshot.Product(
                451396, "Lugia VSTAR", 3170, "139", List.of("holofoil")));
    assertThat(tcgCsvClient.updateMarkerCalls()).isEqualTo(2);
    verify(tcgCsvClient).findGroups(3);
    assertThat(tcgCsvClient.productCalls()).isEqualTo(1);
    assertThat(tcgCsvClient.priceCalls()).isEqualTo(1);
    var readerRepository =
        new CatalogRepository(
            factory
                .dynamoDbEnhancedClient()
                .table(
                    factory.tableDefinition().tableName(),
                    TableSchema.fromBean(CatalogSnapshotItem.class)),
            factory.dynamoDbClient());
    var readerStore =
        new CatalogSnapshotStore(
            factory.s3Client(),
            BUCKET,
            readerRepository,
            new TcgCsvCatalogArtifactCodec(new ObjectMapper()));
    var lookup = new PokemonPrintingLookup(readerRepository, readerStore, factory.clock());
    var service = new GetCardService(factory.gamesService(), null, lookup);
    var card =
        service
            .getCard(new RequestContext("alice"), new GetCardRequest("pokemon", "451396"))
            .card();
    assertThat(card.setCode()).isNull();
    assertThat(card.name()).isEqualTo("Lugia VSTAR");
    assertThat(card.availableFinishes()).containsExactly("holofoil");
    assertThat(factory.tableDefinition().scan().stream().flatMap(page -> page.items().stream()))
        .singleElement()
        .extracting(item -> item.getPk() + "/" + item.getSk())
        .isEqualTo("CATALOG#pokemon/SNAPSHOT#2026-10-07T20:06:09Z");
  }

  @Test
  void handleRequestShouldPublishBothGamesIndependentlyAtTheSameMarker() throws IOException {
    // arrange
    handler.handleRequest(event(REFRESH_BODY), null);

    // act
    handler.handleRequest(event(JAPANESE_REFRESH_BODY), null);
    handler.handleRequest(event(JAPANESE_REFRESH_BODY), null);

    // assert
    var english = repository.getLatestSnapshot("pokemon");
    var japanese = repository.getLatestSnapshot("pokemon_jp");
    assertThat(japanese.getPk()).isEqualTo("CATALOG#pokemon_jp");
    assertThat(japanese.getSnapshotId()).isEqualTo(english.getSnapshotId());
    assertThat(japanese.getS3Key())
        .isEqualTo("catalogs/tcgcsv/pokemon_jp/snapshots/" + english.getSnapshotId() + ".json.gz");
    assertThat(store.getSnapshot(english).categoryId()).isEqualTo(3);
    var snapshot = store.getSnapshot(japanese);
    assertThat(snapshot.game()).isEqualTo("pokemon_jp");
    assertThat(snapshot.categoryId()).isEqualTo(85);
    assertThat(snapshot.groups())
        .containsExactly(new TcgCsvCatalogSnapshot.Group(23599, null, "Pokémon Card 151"));
    assertThat(snapshot.products())
        .containsExactly(
            new TcgCsvCatalogSnapshot.Product(
                566838, "Snorlax (Master Ball Pattern)", 23599, "143/165", List.of("holofoil")));
    assertThat(tcgCsvClient.updateMarkerCalls()).isEqualTo(5);
    assertThat(tcgCsvClient.groupCalls()).isEqualTo(2);
    assertThat(tcgCsvClient.productCalls()).isEqualTo(2);
    assertThat(tcgCsvClient.priceCalls()).isEqualTo(2);
    verify(tcgCsvClient).findGroups(85);
    verify(tcgCsvClient).findPrices(85, 23599);
  }

  @Test
  void handleRequestShouldSkipDuplicateDeliveryAfterPublication() {
    // arrange

    // act
    handler.handleRequest(event(REFRESH_BODY), null);
    handler.handleRequest(event(REFRESH_BODY), null);

    // assert
    assertThat(repository.getLatestSnapshot("pokemon")).isNotNull();
    assertThat(tcgCsvClient.updateMarkerCalls()).isEqualTo(3);
    assertThat(tcgCsvClient.groupCalls()).isEqualTo(1);
    assertThat(tcgCsvClient.productCalls()).isEqualTo(1);
    assertThat(tcgCsvClient.priceCalls()).isEqualTo(1);
  }

  @Test
  void handleRequestShouldSkipWhenMarkerIsAlreadyPublished() throws IOException {
    // arrange
    store.createSnapshot(snapshot(CURRENT_MARKER));

    // act
    handler.handleRequest(event(REFRESH_BODY), null);

    // assert
    assertThat(tcgCsvClient.updateMarkerCalls()).isEqualTo(1);
    assertThat(tcgCsvClient.groupCalls()).isZero();
    assertThat(tcgCsvClient.productCalls()).isZero();
    assertThat(tcgCsvClient.priceCalls()).isZero();
  }

  @Test
  void handleRequestShouldSkipWhenLatestPublicationIsNewerThanMarker() throws IOException {
    // arrange
    store.createSnapshot(snapshot(NEXT_MARKER));

    // act
    handler.handleRequest(event(REFRESH_BODY), null);

    // assert
    assertThat(tcgCsvClient.groupCalls()).isZero();
    assertThat(repository.getLatestSnapshot("pokemon").getSnapshotId())
        .isEqualTo(NEXT_MARKER.updatedAt().toString());
  }

  @Test
  void handleRequestShouldRejectSourceChangeBeforePublication() {
    // arrange
    tcgCsvClient.setUpdateMarkers(CURRENT_MARKER, NEXT_MARKER);

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(event(JAPANESE_REFRESH_BODY), null))
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage(
            "TCGCSV source changed during catalog refresh: "
                + CURRENT_MARKER.updatedAt()
                + " -> "
                + NEXT_MARKER.updatedAt());
    assertThat(repository.getLatestSnapshot("pokemon_jp")).isNull();
  }

  @Test
  void handleRequestShouldPropagateCollectionFailureAndRetryTheWholePull()
      throws IOException, InterruptedException {
    // arrange
    var failure = new IOException("temporary source failure");
    doThrow(failure).doCallRealMethod().when(tcgCsvClient).findProducts(3, 3170);

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(event(REFRESH_BODY), null)).hasCause(failure);
    assertThat(repository.getLatestSnapshot("pokemon")).isNull();

    // act
    handler.handleRequest(event(REFRESH_BODY), null);

    // assert
    assertThat(tcgCsvClient.groupCalls()).isEqualTo(2);
    assertThat(tcgCsvClient.productCalls()).isEqualTo(1);
    assertThat(repository.getLatestSnapshot("pokemon")).isNotNull();
  }

  @Test
  void handleRequestShouldRejectMalformedMessagesAndUnsupportedGames() throws Exception {
    // arrange

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(event("{not-json"), null))
        .hasCauseInstanceOf(IOException.class);
    assertThatThrownBy(
            () ->
                handler.handleRequest(
                    event(
                        new ObjectMapper()
                            .writeValueAsString(
                                new CatalogRefreshMessage("pokemon", "not-a-timestampZ"))),
                    null))
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                handler.handleRequest(
                    event(
                        new ObjectMapper()
                            .writeValueAsString(
                                new CatalogRefreshMessage(
                                    "pokemon-japanese", "2026-10-08T21:00:00Z"))),
                    null))
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThat(tcgCsvClient.updateMarkerCalls()).isZero();
  }

  @Test
  void handleRequestShouldRequireOneMessageAndUtcSchedule() throws Exception {
    // arrange
    var emptyEvent = event("");
    emptyEvent.setRecords(List.of());
    var multipleMessages = event("");
    multipleMessages.setRecords(List.of(new SQSEvent.SQSMessage(), new SQSEvent.SQSMessage()));
    var offsetSchedule =
        event(
            new ObjectMapper()
                .writeValueAsString(
                    new CatalogRefreshMessage("pokemon", "2026-10-08T22:00:00+01:00")));

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(emptyEvent, null))
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> handler.handleRequest(multipleMessages, null))
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> handler.handleRequest(offsetSchedule, null))
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("scheduled_at must be a UTC timestamp");
    assertThat(tcgCsvClient.updateMarkerCalls()).isZero();
  }

  @Test
  void handleRequestShouldKeepPriorJapaneseSnapshotReadableUntilPublicationRetries()
      throws IOException {
    // arrange
    handler.handleRequest(event(JAPANESE_REFRESH_BODY), null);
    var previous = repository.getLatestSnapshot("pokemon_jp");
    var previousSnapshot = store.getSnapshot(previous);
    tcgCsvClient.setUpdateMarkers(NEXT_MARKER);
    var failure = DynamoDbException.builder().message("registry unavailable").build();
    doThrow(failure).doCallRealMethod().when(repository).createSnapshot(any());
    var key = CatalogSnapshotStore.key("pokemon_jp", NEXT_MARKER.updatedAt().toString());

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(event(JAPANESE_REFRESH_BODY), null))
        .hasCause(failure);
    var latest = repository.getLatestSnapshot("pokemon_jp");
    assertThat(latest.getSnapshotId()).isEqualTo(previous.getSnapshotId());
    assertThat(store.getSnapshot(latest)).isEqualTo(previousSnapshot);
    assertThat(factory.s3Client().headObject(request -> request.bucket(BUCKET).key(key)))
        .isNotNull();

    // act
    factory.fakeClock().setTime(factory.clock().now().plusSeconds(1));
    handler.handleRequest(event(JAPANESE_REFRESH_BODY), null);

    // assert
    var item = repository.getLatestSnapshot("pokemon_jp");
    assertThat(item.getS3Key()).isEqualTo(key);
    assertThat(item.getCreatedAt()).isEqualTo(factory.clock().now().minusSeconds(1));
    assertThat(tcgCsvClient.groupCalls()).isEqualTo(3);
  }

  @Test
  void handleRequestShouldRestoreInterruptAndFailInvocation() throws InterruptedException {
    // arrange
    var interruption = new InterruptedException("interrupted source request");
    doThrow(interruption).when(tcgCsvClient).getUpdateMarker();

    // act / assert
    try {
      assertThatThrownBy(() -> handler.handleRequest(event(REFRESH_BODY), null))
          .hasCause(interruption);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private TcgCsvCatalogSnapshot snapshot(TcgCsvClient.UpdateMarker marker) {
    return new TcgCsvCatalogNormalizer()
        .normalize(
            new TcgCsvCatalogDefinition(
                "pokemon", 3, List.of(new FinishMapping("Holofoil", "holofoil"))),
            marker,
            Instant.parse("2026-10-08T21:01:00Z"),
            List.of(new TcgCsvClient.Group(3170, 3, "Silver Tempest", "SWSH12")),
            List.of(
                new TcgCsvCatalogNormalizer.GroupSource(
                    new TcgCsvClient.Group(3170, 3, "Silver Tempest", "SWSH12"),
                    List.of(
                        new TcgCsvClient.Product(
                            451396,
                            3,
                            3170,
                            "Lugia VSTAR",
                            List.of(
                                new TcgCsvClient.ExtendedData("Card Type", "Pokémon"),
                                new TcgCsvClient.ExtendedData("Number", "139")))),
                    List.of(new TcgCsvClient.Price(451396, "Holofoil")))));
  }

  private SQSEvent event(String body) {
    var message = new SQSEvent.SQSMessage();
    message.setBody(body);
    var event = new SQSEvent();
    event.setRecords(List.of(message));
    return event;
  }

  private static class FakeTcgCsvClient implements TcgCsvClient {
    private List<UpdateMarker> updateMarkers = List.of(CURRENT_MARKER);
    private int updateMarkerCalls;
    private int groupCalls;
    private int productCalls;
    private int priceCalls;

    @Override
    public UpdateMarker getUpdateMarker() throws InterruptedException {
      var index = Math.min(updateMarkerCalls, updateMarkers.size() - 1);
      updateMarkerCalls++;
      return updateMarkers.get(index);
    }

    @Override
    public List<Group> findGroups(int categoryId) {
      groupCalls++;
      if (categoryId == 85) {
        return List.of(new Group(23599, 85, "Pokémon Card 151", ""));
      }
      return List.of(new Group(3170, categoryId, "Silver Tempest", "SWSH12"));
    }

    @Override
    public List<Product> findProducts(int categoryId, int groupId) throws IOException {
      productCalls++;
      if (categoryId == 85) {
        return List.of(
            new Product(
                566838,
                85,
                23599,
                "Snorlax (Master Ball Pattern)",
                List.of(
                    new ExtendedData("CardType", "Colorless"),
                    new ExtendedData("Number", "143/165"))));
      }
      return List.of(
          new Product(
              451396,
              categoryId,
              groupId,
              "Lugia VSTAR",
              List.of(
                  new ExtendedData("Card Type", "Pokémon"), new ExtendedData("Number", "139"))));
    }

    @Override
    public List<Price> findPrices(int categoryId, int groupId) {
      priceCalls++;
      if (categoryId == 85) {
        return List.of(new Price(566838, "Holofoil"));
      }
      return List.of(new Price(451396, "Holofoil"));
    }

    public void setUpdateMarkers(UpdateMarker... updateMarkers) {
      this.updateMarkers = List.of(updateMarkers);
      this.updateMarkerCalls = 0;
    }

    public int updateMarkerCalls() {
      return updateMarkerCalls;
    }

    public int groupCalls() {
      return groupCalls;
    }

    public int productCalls() {
      return productCalls;
    }

    public int priceCalls() {
      return priceCalls;
    }
  }
}
