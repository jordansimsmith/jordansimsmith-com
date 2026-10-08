package com.jordansimsmith.tcginventory.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.s3.S3Container;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class CatalogRefreshProcessorIntegrationTest {
  private static final Instant SCHEDULED_AT = Instant.parse("2026-10-08T21:00:00Z");
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
  private CatalogRefreshProcessor processor;

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
    tcgCsvClient = factory.fakeTcgCsvClient();
    tcgCsvClient.setUpdateMarkers(CURRENT_MARKER);
    processor =
        new CatalogRefreshProcessor(
            tcgCsvClient, new TcgCsvCatalogNormalizer(), store, repository, factory.clock());
  }

  @Test
  void processShouldFetchNormalizeAndPublishCompleteCatalog()
      throws IOException, InterruptedException {
    // arrange

    // act
    processor.process("pokemon", SCHEDULED_AT);

    // assert
    var latest = repository.getLatestSnapshot("pokemon");
    assertThat(latest).isNotNull();
    var snapshot = store.getSnapshot(latest);
    assertThat(snapshot.sourceMarker()).isEqualTo(CURRENT_MARKER.sourceMarker());
    assertThat(snapshot.groups())
        .containsExactly(new TcgCsvCatalogSnapshot.Group(3170, "SWSH12", "Silver Tempest"));
    assertThat(snapshot.products())
        .containsExactly(
            new TcgCsvCatalogSnapshot.Product(
                451396, "Lugia VSTAR", 3170, "139", List.of("holofoil")));
    assertThat(tcgCsvClient.updateMarkerCalls()).isEqualTo(2);
    assertThat(tcgCsvClient.groupCalls()).isEqualTo(1);
    assertThat(tcgCsvClient.productCalls()).isEqualTo(1);
    assertThat(tcgCsvClient.priceCalls()).isEqualTo(1);
    assertThat(factory.tableDefinition().scan().stream().flatMap(page -> page.items().stream()))
        .singleElement()
        .extracting(item -> item.getPk() + "/" + item.getSk())
        .isEqualTo("CATALOG#pokemon/SNAPSHOT#2026-10-07T20:06:09Z");
  }

  @Test
  void processShouldSkipDuplicateDeliveryAfterPublication()
      throws IOException, InterruptedException {
    // arrange

    // act
    processor.process("pokemon", SCHEDULED_AT);
    processor.process("pokemon", SCHEDULED_AT);

    // assert
    assertThat(repository.getLatestSnapshot("pokemon")).isNotNull();
    assertThat(tcgCsvClient.updateMarkerCalls()).isEqualTo(3);
    assertThat(tcgCsvClient.groupCalls()).isEqualTo(1);
    assertThat(tcgCsvClient.productCalls()).isEqualTo(1);
    assertThat(tcgCsvClient.priceCalls()).isEqualTo(1);
  }

  @Test
  void processShouldSkipWhenMarkerIsAlreadyPublished() throws IOException, InterruptedException {
    // arrange
    store.createSnapshot(snapshot(CURRENT_MARKER));

    // act
    processor.process("pokemon", SCHEDULED_AT);

    // assert
    assertThat(tcgCsvClient.updateMarkerCalls()).isEqualTo(1);
    assertThat(tcgCsvClient.groupCalls()).isZero();
    assertThat(tcgCsvClient.productCalls()).isZero();
    assertThat(tcgCsvClient.priceCalls()).isZero();
  }

  @Test
  void processShouldSkipWhenLatestPublicationIsNewerThanMarker()
      throws IOException, InterruptedException {
    // arrange
    store.createSnapshot(snapshot(NEXT_MARKER));

    // act
    processor.process("pokemon", SCHEDULED_AT);

    // assert
    assertThat(tcgCsvClient.groupCalls()).isZero();
    assertThat(repository.getLatestSnapshot("pokemon").getSnapshotId())
        .isEqualTo(NEXT_MARKER.updatedAt().toString());
  }

  @Test
  void processShouldRejectSourceChangeBeforePublication() {
    // arrange
    tcgCsvClient.setUpdateMarkers(CURRENT_MARKER, NEXT_MARKER);

    // act / assert
    assertThatThrownBy(() -> processor.process("pokemon", SCHEDULED_AT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source changed");
    assertThat(repository.getLatestSnapshot("pokemon")).isNull();
  }

  @Test
  void processShouldPropagateCollectionFailureAndRetryTheWholePull()
      throws IOException, InterruptedException {
    // arrange
    var failure = new IOException("temporary source failure");
    tcgCsvClient.setProductFailure(failure);

    // act / assert
    assertThatThrownBy(() -> processor.process("pokemon", SCHEDULED_AT)).isSameAs(failure);
    assertThat(repository.getLatestSnapshot("pokemon")).isNull();

    // arrange
    tcgCsvClient.setProductFailure(null);

    // act
    processor.process("pokemon", SCHEDULED_AT);

    // assert
    assertThat(tcgCsvClient.groupCalls()).isEqualTo(2);
    assertThat(tcgCsvClient.productCalls()).isEqualTo(2);
    assertThat(repository.getLatestSnapshot("pokemon")).isNotNull();
  }

  @Test
  void handlerShouldRejectMalformedMessagesAndUnsupportedGames() throws Exception {
    // arrange
    var handler = new CatalogJobsHandler(factory);

    // act / assert
    assertThatThrownBy(() -> handler.handleRequest(event("{not-json"), null))
        .hasCauseInstanceOf(IOException.class);
    assertThatThrownBy(
            () ->
                handler.handleRequest(
                    event(
                        new ObjectMapper()
                            .writeValueAsString(
                                new CatalogRefreshMessage("pokemon", "not-a-timestamp"))),
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
  void handlerShouldRequireOneMessageAndUtcSchedule() throws Exception {
    // arrange
    var handler = new CatalogJobsHandler(factory);
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

  private TcgCsvCatalogSnapshot snapshot(TcgCsvClient.UpdateMarker marker) {
    return new TcgCsvCatalogNormalizer()
        .normalize(
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
}
