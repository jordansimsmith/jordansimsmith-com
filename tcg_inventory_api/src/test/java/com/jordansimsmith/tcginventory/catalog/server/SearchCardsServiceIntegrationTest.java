package com.jordansimsmith.tcginventory.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.s3.S3Container;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.catalog.client.GetCardRequest;
import com.jordansimsmith.tcginventory.catalog.client.SearchCardsRequest;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.jordansimsmith.time.SystemClock;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;
import software.amazon.awssdk.services.s3.S3Client;

@Testcontainers
public class SearchCardsServiceIntegrationTest {
  private static final String TABLE_NAME = "tcg_inventory";
  private static final String BUCKET = "catalog-snapshots";
  private static final String SNAPSHOT_ID = "2026-10-07T20:06:09Z";

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();
  @Container private static final S3Container s3Container = new S3Container();

  private static DynamoDbClient dynamoDb;
  private static S3Client s3;

  private SearchCardsService searchCardsService;
  private GetCardService getCardService;

  @BeforeAll
  static void setUpBeforeClass() {
    var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("fake", "fake"));
    dynamoDb =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .credentialsProvider(credentials)
            .build();
    s3 =
        S3Client.builder()
            .endpointOverride(s3Container.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .credentialsProvider(credentials)
            .forcePathStyle(true)
            .build();

    dynamoDb.createTable(
        request ->
            request
                .tableName(TABLE_NAME)
                .attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("pk")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                    AttributeDefinition.builder()
                        .attributeName("sk")
                        .attributeType(ScalarAttributeType.S)
                        .build())
                .keySchema(
                    KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                    KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .provisionedThroughput(
                    ProvisionedThroughput.builder()
                        .readCapacityUnits(5L)
                        .writeCapacityUnits(5L)
                        .build()));
    try (var waiter = DynamoDbWaiter.builder().client(dynamoDb).build()) {
      waiter.waitUntilTableExists(request -> request.tableName(TABLE_NAME)).matched();
    }
    s3.createBucket(request -> request.bucket(BUCKET));
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDb);
    s3.listObjectsV2(request -> request.bucket(BUCKET))
        .contents()
        .forEach(object -> s3.deleteObject(request -> request.bucket(BUCKET).key(object.key())));
    var pokemon =
        new PokemonPrintingLookup(
            dynamoDb, s3, TABLE_NAME, BUCKET, new ObjectMapper(), new SystemClock());
    var games = new FakeGamesService();
    searchCardsService = new SearchCardsService(games, null, pokemon);
    getCardService = new GetCardService(games, null, pokemon);
  }

  @Test
  void searchCardsShouldReadPublishedSnapshotAndShareDetailCache() throws Exception {
    // arrange
    publishSnapshot(SNAPSHOT_ID, "Pikachu", 41);
    var context = new RequestContext("alice");
    var detail = getCardService.getCard(context, new GetCardRequest("pokemon", "1"));
    publishSnapshot("2026-10-07T20:07:09Z", "Raichu", 1);

    // act
    var first =
        searchCardsService.searchCards(
            context, new SearchCardsRequest("pokemon", "  PIKACHU  ", "normal", null, null));
    var second =
        searchCardsService.searchCards(
            context,
            new SearchCardsRequest("pokemon", "Pikachu", "normal", null, first.nextContinuation()));
    var third =
        searchCardsService.searchCards(
            context,
            new SearchCardsRequest(
                "pokemon", "Pikachu", "normal", null, second.nextContinuation()));

    // assert
    assertThat(detail.card().name()).isEqualTo("Pikachu 1");
    assertThat(first.cards())
        .extracting(card -> card.externalId())
        .containsExactlyElementsOf(
            IntStream.rangeClosed(1, 20).mapToObj(Integer::toString).toList());
    assertThat(first.cards().getFirst().availableFinishes()).containsExactly("normal", "holofoil");
    assertThat(first.cards().getFirst().imageUrls().small())
        .isEqualTo("https://tcgplayer-cdn.tcgplayer.com/product/1_200w.jpg");
    assertThat(second.cards())
        .extracting(card -> card.externalId())
        .containsExactlyElementsOf(
            IntStream.rangeClosed(21, 40).mapToObj(Integer::toString).toList());
    assertThat(third.cards()).extracting(card -> card.externalId()).containsExactly("41");
    assertThat(third.nextContinuation()).isNull();
    assertThatThrownBy(
            () ->
                searchCardsService.searchCards(
                    context,
                    new SearchCardsRequest("pokemon", "Pikachu", "normal", null, "not-base64!")))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                searchCardsService.searchCards(
                    context, new SearchCardsRequest("pokemon", "Pikachu", "normal", null, "NDI")))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void searchCardsShouldReturnUnavailableWhenNoSnapshotIsPublished() {
    // arrange / act / assert
    assertThatThrownBy(
            () ->
                searchCardsService.searchCards(
                    new RequestContext("alice"),
                    new SearchCardsRequest("pokemon", "Pikachu", "normal", null, null)))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("catalog is temporarily unavailable")
        .hasCauseInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void searchCardsShouldUseRequestedLimit() throws Exception {
    // arrange
    publishSnapshot(SNAPSHOT_ID, "Pikachu", 13);

    // act
    var response =
        searchCardsService.searchCards(
            new RequestContext("alice"),
            new SearchCardsRequest("pokemon", "Pikachu", "normal", 5, null));

    // assert
    assertThat(response.cards()).hasSize(5);
    assertThat(response.nextContinuation()).isNotNull();
  }

  private static void publishSnapshot(String snapshotId, String name, int productCount)
      throws Exception {
    var updatedAt = Instant.parse(snapshotId);
    var products =
        IntStream.rangeClosed(1, productCount)
            .mapToObj(
                id ->
                    Map.of(
                        "product_id",
                        id,
                        "name",
                        name + " " + id,
                        "group_id",
                        1,
                        "collector_number",
                        Integer.toString(id),
                        "available_finishes",
                        List.of("normal", "holofoil")))
            .toList();
    var snapshot =
        Map.ofEntries(
            Map.entry("schema_version", 1),
            Map.entry("game", "pokemon"),
            Map.entry("external_source", "tcgplayer"),
            Map.entry("catalog_source", "tcgcsv"),
            Map.entry("category_id", 3),
            Map.entry("snapshot_id", snapshotId),
            Map.entry(
                "source_marker",
                DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxx")
                    .withZone(ZoneOffset.UTC)
                    .format(updatedAt)),
            Map.entry("source_updated_at", updatedAt.getEpochSecond()),
            Map.entry("created_at", updatedAt.getEpochSecond()),
            Map.entry(
                "groups",
                List.of(Map.of("group_id", 1, "set_code", "BASE", "set_name", "Base Set"))),
            Map.entry("products", products));
    var json = new ObjectMapper().writeValueAsBytes(snapshot);
    var compressed = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(compressed)) {
      gzip.write(json);
    }
    var key = "catalogs/tcgcsv/pokemon/snapshots/" + snapshotId + ".json.gz";
    s3.putObject(
        request -> request.bucket(BUCKET).key(key),
        RequestBody.fromBytes(compressed.toByteArray()));
    dynamoDb.putItem(
        request ->
            request
                .tableName(TABLE_NAME)
                .item(
                    Map.of(
                        "pk", AttributeValue.fromS("CATALOG#pokemon"),
                        "sk", AttributeValue.fromS("SNAPSHOT#" + snapshotId),
                        "game", AttributeValue.fromS("pokemon"),
                        "snapshot_id", AttributeValue.fromS(snapshotId),
                        "s3_key", AttributeValue.fromS(key),
                        "checksum_sha256", AttributeValue.fromS("snapshot-checksum"),
                        "source_updated_at",
                            AttributeValue.fromN(Long.toString(updatedAt.getEpochSecond())),
                        "created_at",
                            AttributeValue.fromN(Long.toString(updatedAt.getEpochSecond())))));
  }
}
