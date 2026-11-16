package com.jordansimsmith.tcginventory.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.s3.S3Container;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.catalog.client.FindCardAlternativesRequest;
import com.jordansimsmith.tcginventory.catalog.client.FindCardImagesRequest;
import com.jordansimsmith.tcginventory.catalog.client.FindCardsRequest;
import com.jordansimsmith.tcginventory.catalog.client.GetCardRequest;
import com.jordansimsmith.tcginventory.catalog.client.SearchCardsRequest;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.jordansimsmith.tcginventory.games.client.Finish;
import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.games.client.Language;
import com.jordansimsmith.tcginventory.games.client.ScanReviewImageRegion;
import com.jordansimsmith.time.FakeClock;
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
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
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
  private FindCardAlternativesService findCardAlternativesService;
  private FindCardsService findCardsService;
  private FindCardImagesService findCardImagesService;
  private FakeClock clock;

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
    var table =
        DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDb)
            .build()
            .table(TABLE_NAME, TableSchema.fromBean(CatalogSnapshotItem.class));
    var repository = new CatalogRepository(table, dynamoDb);
    var store =
        new CatalogSnapshotStore(
            s3, BUCKET, repository, new TcgCsvCatalogArtifactCodec(new ObjectMapper()));
    clock = new FakeClock();
    clock.setTime(Instant.parse("2026-10-07T21:00:00Z"));
    var pokemon = new TcgCsvPrintingLookup(repository, store, clock);
    var games = new FakeGamesService();
    games.seed(japanesePokemonGame());
    searchCardsService = new SearchCardsService(games, null, pokemon);
    getCardService = new GetCardService(games, null, pokemon);
    findCardAlternativesService = new FindCardAlternativesService(games, null, pokemon);
    findCardsService = new FindCardsService(games, null, pokemon);
    findCardImagesService = new FindCardImagesService(games);
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
    assertThat(first.cards().getFirst()).isEqualTo(detail.card());
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
        .hasMessage("catalog is temporarily unavailable");
    assertThatThrownBy(
            () ->
                findCardAlternativesService.findCardAlternatives(
                    new RequestContext("alice"),
                    new FindCardAlternativesRequest("pokemon", "25", "normal", null, null)))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("catalog is temporarily unavailable");
  }

  @Test
  void findCardsShouldRejectSnapshotsWithDuplicateProductsOrMissingGroups() throws Exception {
    // arrange
    var context = new RequestContext("alice");
    var request = new FindCardsRequest("pokemon", List.of("25"));
    var group = Map.of("group_id", 1, "set_code", "BASE", "set_name", "Base Set");
    var product =
        Map.of(
            "product_id",
            25,
            "name",
            "Pikachu",
            "group_id",
            1,
            "collector_number",
            "25",
            "available_finishes",
            List.of("normal"));

    // act / assert
    publishSnapshot(SNAPSHOT_ID, List.of(group), List.of(product, product));
    assertThatThrownBy(() -> findCardsService.findCards(context, request))
        .isInstanceOf(ServiceUnavailableException.class);
    publishSnapshot("2026-10-07T20:07:09Z", List.of(), List.of(product));
    assertThatThrownBy(() -> findCardsService.findCards(context, request))
        .isInstanceOf(ServiceUnavailableException.class);
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

  @Test
  void findCardAlternativesShouldGroupExactNamesAndKeepAnchorFirstAcrossPages() throws Exception {
    // arrange
    publishAlternativesSnapshot(SNAPSHOT_ID);
    var context = new RequestContext("alice");

    // act
    var first =
        findCardAlternativesService.findCardAlternatives(
            context, new FindCardAlternativesRequest("pokemon", "10", "normal", 1, null));
    publishSnapshot("2026-10-07T20:07:09Z", "Raichu", 1);
    var second =
        findCardAlternativesService.findCardAlternatives(
            context,
            new FindCardAlternativesRequest(
                "pokemon", "10", "normal", 1, first.nextContinuation()));

    // assert
    assertThat(first.cards()).extracting(card -> card.externalId()).containsExactly("10");
    assertThat(first.nextContinuation()).isNotNull();
    assertThat(second.cards()).extracting(card -> card.externalId()).containsExactly("2");
    assertThat(second.nextContinuation()).isNull();
    assertThatThrownBy(
            () ->
                findCardAlternativesService.findCardAlternatives(
                    context,
                    new FindCardAlternativesRequest("pokemon", "999", "normal", null, null)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void japanesePokemonCatalogShouldSupportDetailBulkSearchAlternativesAndImages() throws Exception {
    // arrange
    publishSnapshot(
        "pokemon_jp",
        85,
        SNAPSHOT_ID,
        List.of(Map.of("group_id", 3413, "set_name", "Pokémon Card 151")),
        List.of(
            product(566488, "Snorlax", 3413, "143/165", List.of("normal")),
            product(566685, "Snorlax (Poké Ball)", 3413, "143/165", List.of("holofoil")),
            product(566838, "Snorlax (Master Ball)", 3413, "143/165", List.of("holofoil")),
            product(566839, "Snorlax (Master Ball)", 3413, "143/165", List.of("holofoil"))));
    var context = new RequestContext("alice");

    // act
    var detail = getCardService.getCard(context, new GetCardRequest("pokemon_jp", "566838"));
    var bulk =
        findCardsService.findCards(
            context, new FindCardsRequest("pokemon_jp", List.of("566838", "missing")));
    var holofoils =
        searchCardsService.searchCards(
            context, new SearchCardsRequest("pokemon_jp", "Snorlax", "holofoil", null, null));
    var normals =
        searchCardsService.searchCards(
            context, new SearchCardsRequest("pokemon_jp", "Snorlax", "normal", null, null));
    var alternatives =
        findCardAlternativesService.findCardAlternatives(
            context,
            new FindCardAlternativesRequest("pokemon_jp", "566838", "holofoil", null, null));
    var images =
        findCardImagesService.findCardImages(
            context, new FindCardImagesRequest("pokemon_jp", List.of("566838")));

    // assert
    assertThat(detail.card().game()).isEqualTo("pokemon_jp");
    assertThat(detail.card().name()).isEqualTo("Snorlax (Master Ball)");
    assertThat(detail.card().collectorNumber()).isEqualTo("143/165");
    assertThat(detail.card().setCode()).isNull();
    assertThat(detail.card().setName()).isEqualTo("Pokémon Card 151");
    assertThat(detail.card().availableFinishes()).containsExactly("holofoil");
    assertThat(detail.card().imageUrls().small())
        .isEqualTo("https://tcgplayer-cdn.tcgplayer.com/product/566838_200w.jpg");
    assertThat(bulk.cards()).extracting(card -> card.externalId()).containsExactly("566838");
    assertThat(holofoils.cards())
        .extracting(card -> card.externalId())
        .containsExactly("566685", "566838", "566839");
    assertThat(normals.cards()).extracting(card -> card.externalId()).containsExactly("566488");
    assertThat(alternatives.cards())
        .extracting(card -> card.externalId())
        .containsExactly("566838", "566839");
    assertThat(images.images().getFirst().imageUrls().normal())
        .isEqualTo("https://tcgplayer-cdn.tcgplayer.com/product/566838_in_1000x1000.jpg");
    assertThatThrownBy(
            () ->
                searchCardsService.searchCards(
                    context,
                    new SearchCardsRequest(
                        "pokemon_jp", "Snorlax", "reverse_holofoil", null, null)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                findCardAlternativesService.findCardAlternatives(
                    context,
                    new FindCardAlternativesRequest(
                        "pokemon_jp", "566838", "reverse_holofoil", null, null)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void pokemonCatalogCacheShouldRefreshEachGameIndependently() throws Exception {
    // arrange
    publishSnapshot(
        "pokemon", 3, SNAPSHOT_ID, List.of(group("EN Set")), List.of(product(1, "English old")));
    publishSnapshot(
        "pokemon_jp",
        85,
        SNAPSHOT_ID,
        List.of(group("JP Set")),
        List.of(product(1, "Japanese old")));
    var context = new RequestContext("alice");
    var englishOld = getCardService.getCard(context, new GetCardRequest("pokemon", "1"));
    clock.setTime(clock.now().plusSeconds(120));
    var japaneseOld = getCardService.getCard(context, new GetCardRequest("pokemon_jp", "1"));
    publishSnapshot(
        "pokemon",
        3,
        "2026-10-07T20:07:09Z",
        List.of(group("EN Set")),
        List.of(product(1, "English new")));
    publishSnapshot(
        "pokemon_jp",
        85,
        "2026-10-07T20:07:09Z",
        List.of(group("JP Set")),
        List.of(product(1, "Japanese new")));

    // act
    clock.setTime(clock.now().plusSeconds(180));
    var englishNew = getCardService.getCard(context, new GetCardRequest("pokemon", "1"));
    var japaneseStillCached =
        getCardService.getCard(context, new GetCardRequest("pokemon_jp", "1"));
    clock.setTime(clock.now().plusSeconds(120));
    var japaneseNew = getCardService.getCard(context, new GetCardRequest("pokemon_jp", "1"));

    // assert
    assertThat(englishOld.card().name()).isEqualTo("English old");
    assertThat(japaneseOld.card().name()).isEqualTo("Japanese old");
    assertThat(englishNew.card().name()).isEqualTo("English new");
    assertThat(japaneseStillCached.card().name()).isEqualTo("Japanese old");
    assertThat(japaneseNew.card().name()).isEqualTo("Japanese new");
  }

  @Test
  void missingJapaneseCatalogShouldNotAffectEnglishAndCanLoadAfterPublication() throws Exception {
    // arrange
    publishSnapshot(
        "pokemon", 3, SNAPSHOT_ID, List.of(group("EN Set")), List.of(product(1, "English card")));
    var context = new RequestContext("alice");

    // act / assert
    assertThatThrownBy(() -> getCardService.getCard(context, new GetCardRequest("pokemon_jp", "1")))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(getCardService.getCard(context, new GetCardRequest("pokemon", "1")).card().name())
        .isEqualTo("English card");
    publishSnapshot(
        "pokemon_jp",
        85,
        SNAPSHOT_ID,
        List.of(group("JP Set")),
        List.of(product(1, "Japanese card")));
    assertThat(getCardService.getCard(context, new GetCardRequest("pokemon_jp", "1")).card().name())
        .isEqualTo("Japanese card");
  }

  @Test
  void japaneseCatalogShouldRejectMismatchedSnapshotMetadataWithoutAffectingEnglish()
      throws Exception {
    // arrange
    publishSnapshot(
        "pokemon", 3, SNAPSHOT_ID, List.of(group("EN Set")), List.of(product(1, "English card")));
    publishSnapshot(
        "pokemon_jp",
        3,
        SNAPSHOT_ID,
        List.of(group("Wrong category")),
        List.of(product(1, "Wrong card")));
    var context = new RequestContext("alice");

    // act / assert
    assertThatThrownBy(() -> getCardService.getCard(context, new GetCardRequest("pokemon_jp", "1")))
        .isInstanceOf(ServiceUnavailableException.class);
    publishSnapshot(
        "pokemon_jp",
        85,
        "2026-10-07T20:07:09Z",
        List.of(group("Wrong external source")),
        List.of(product(1, "Wrong source card")),
        "other_source",
        "tcgcsv");
    assertThatThrownBy(() -> getCardService.getCard(context, new GetCardRequest("pokemon_jp", "1")))
        .isInstanceOf(ServiceUnavailableException.class);
    publishSnapshot(
        "pokemon_jp",
        85,
        "2026-10-07T20:08:09Z",
        List.of(group("Wrong catalog source")),
        List.of(product(1, "Wrong catalog card")),
        "tcgplayer",
        "other_catalog");
    assertThatThrownBy(() -> getCardService.getCard(context, new GetCardRequest("pokemon_jp", "1")))
        .isInstanceOf(ServiceUnavailableException.class);
    assertThat(getCardService.getCard(context, new GetCardRequest("pokemon", "1")).card().name())
        .isEqualTo("English card");
    publishSnapshot(
        "pokemon_jp",
        85,
        "2026-10-07T20:09:09Z",
        List.of(group("JP Set")),
        List.of(product(1, "Japanese corrected card")));
    assertThat(getCardService.getCard(context, new GetCardRequest("pokemon_jp", "1")).card().name())
        .isEqualTo("Japanese corrected card");
  }

  private static Game japanesePokemonGame() {
    return new Game(
        "pokemon_jp",
        "Pokémon (JP)",
        "tcgplayer",
        Language.JAPANESE,
        true,
        false,
        List.of(new Finish("normal", "Normal"), new Finish("holofoil", "Holofoil")),
        List.of(new ScanReviewImageRegion("set_and_number", "Set and number", 0, 0.88, 0.5, 0.12)));
  }

  private static Map<String, Object> group(String name) {
    return Map.of("group_id", 1, "set_code", "SET", "set_name", name);
  }

  private static Map<String, Object> product(int id, String name) {
    return product(id, name, 1, Integer.toString(id), List.of("normal", "holofoil"));
  }

  private static Map<String, Object> product(
      int id, String name, int groupId, String collectorNumber, List<String> finishes) {
    return Map.of(
        "product_id", id,
        "name", name,
        "group_id", groupId,
        "collector_number", collectorNumber,
        "available_finishes", finishes);
  }

  private static void publishAlternativesSnapshot(String snapshotId) throws Exception {
    var products =
        List.of(
            Map.of(
                "product_id",
                10,
                "name",
                "Pikachu",
                "group_id",
                1,
                "collector_number",
                "10",
                "available_finishes",
                List.of("normal", "holofoil")),
            Map.of(
                "product_id",
                2,
                "name",
                "Pikachu",
                "group_id",
                1,
                "collector_number",
                "2",
                "available_finishes",
                List.of("normal")),
            Map.of(
                "product_id",
                4,
                "name",
                "Pikachu",
                "group_id",
                1,
                "collector_number",
                "4",
                "available_finishes",
                List.of("holofoil")),
            Map.of(
                "product_id",
                5,
                "name",
                "pikachu",
                "group_id",
                1,
                "collector_number",
                "5",
                "available_finishes",
                List.of("normal")));
    publishSnapshot(
        "pokemon",
        3,
        snapshotId,
        List.of(Map.of("group_id", 1, "set_code", "BASE", "set_name", "Base Set")),
        products);
  }

  private static void publishSnapshot(String snapshotId, String name, int productCount)
      throws Exception {
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
    publishSnapshot(
        "pokemon",
        3,
        snapshotId,
        List.of(Map.of("group_id", 1, "set_code", "BASE", "set_name", "Base Set")),
        products);
  }

  private static void publishSnapshot(String snapshotId, List<?> groups, List<?> products)
      throws Exception {
    publishSnapshot("pokemon", 3, snapshotId, groups, products);
  }

  private static void publishSnapshot(
      String game, int categoryId, String snapshotId, List<?> groups, List<?> products)
      throws Exception {
    publishSnapshot(game, categoryId, snapshotId, groups, products, "tcgplayer", "tcgcsv");
  }

  private static void publishSnapshot(
      String game,
      int categoryId,
      String snapshotId,
      List<?> groups,
      List<?> products,
      String externalSource,
      String catalogSource)
      throws Exception {
    var updatedAt = Instant.parse(snapshotId);
    var snapshot =
        Map.ofEntries(
            Map.entry("schema_version", 1),
            Map.entry("game", game),
            Map.entry("external_source", externalSource),
            Map.entry("catalog_source", catalogSource),
            Map.entry("category_id", categoryId),
            Map.entry("snapshot_id", snapshotId),
            Map.entry(
                "source_marker",
                DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxx")
                    .withZone(ZoneOffset.UTC)
                    .format(updatedAt)),
            Map.entry("source_updated_at", updatedAt.getEpochSecond()),
            Map.entry("created_at", updatedAt.getEpochSecond()),
            Map.entry("groups", groups),
            Map.entry("products", products));
    var json = new ObjectMapper().writeValueAsBytes(snapshot);
    var compressed = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(compressed)) {
      gzip.write(json);
    }
    var key = "catalogs/tcgcsv/" + game + "/snapshots/" + snapshotId + ".json.gz";
    s3.putObject(
        request -> request.bucket(BUCKET).key(key),
        RequestBody.fromBytes(compressed.toByteArray()));
    dynamoDb.putItem(
        request ->
            request
                .tableName(TABLE_NAME)
                .item(
                    Map.of(
                        "pk", AttributeValue.fromS("CATALOG#" + game),
                        "sk", AttributeValue.fromS("SNAPSHOT#" + snapshotId),
                        "game", AttributeValue.fromS(game),
                        "snapshot_id", AttributeValue.fromS(snapshotId),
                        "s3_key", AttributeValue.fromS(key),
                        "checksum_sha256", AttributeValue.fromS("snapshot-checksum"),
                        "source_updated_at",
                            AttributeValue.fromN(Long.toString(updatedAt.getEpochSecond())),
                        "created_at",
                            AttributeValue.fromN(Long.toString(updatedAt.getEpochSecond())))));
  }
}
