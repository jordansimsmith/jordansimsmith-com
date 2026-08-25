package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.s3.S3Container;
import com.jordansimsmith.ulid.FakeUlidGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Testcontainers
public class RowPhotoHandlerIntegrationTest {
  private static final byte[] JPEG_BYTES =
      new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};

  private FakeUlidGenerator fakeUlidGenerator;
  private ObjectMapper objectMapper;
  private DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private S3Client s3Client;

  private CreateImportRowPhotoHandler createImportRowPhotoHandler;
  private DeleteImportRowPhotoHandler deleteImportRowPhotoHandler;
  private GetImportHandler getImportHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();
  @Container private static final S3Container s3Container = new S3Container();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tcgInventoryTable());
    factory.s3Client().createBucket(b -> b.bucket(Photos.BUCKET));
  }

  @BeforeEach
  void setUp() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());

    fakeUlidGenerator = factory.fakeUlidGenerator();
    objectMapper = factory.objectMapper();
    tcgInventoryTable = factory.tcgInventoryTable();
    s3Client = factory.s3Client();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeUlidGenerator.reset();

    createImportRowPhotoHandler = new CreateImportRowPhotoHandler(factory);
    deleteImportRowPhotoHandler = new DeleteImportRowPhotoHandler(factory);
    getImportHandler = new GetImportHandler(factory);
  }

  @Test
  void createPhotoShouldStoreObjectAndReturnNoContent() {
    // arrange
    var importId = seedImport("jordan", "review", "keep", "25.00");

    // act
    var response =
        createImportRowPhotoHandler.handleRequest(
            buildCreateEvent("jordan", importId, 1, JPEG_BYTES), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);

    var stored =
        s3Client
            .getObjectAsBytes(
                r -> r.bucket(Photos.BUCKET).key(Photos.key("jordan", "FAKE_ULID_0000000001")))
            .asByteArray();
    assertThat(stored).isEqualTo(JPEG_BYTES);

    var rowItem = getRow("jordan", importId, 1);
    assertThat(rowItem.getPhotos()).hasSize(1);
    assertThat(rowItem.getPhotos().get(0).getPhotoId()).isEqualTo("FAKE_ULID_0000000001");
    assertThat(rowItem.getPhotos().get(0).getFetchtcgUrl()).isNull();
  }

  @Test
  void createPhotoShouldRejectNonJpeg() {
    // arrange
    var importId = seedImport("jordan", "review", "keep", "25.00");

    // act
    var response =
        createImportRowPhotoHandler.handleRequest(
            buildCreateEvent("jordan", importId, 1, JPEG_BYTES, "image/png"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void createPhotoShouldRejectOversizedBody() {
    // arrange
    var importId = seedImport("jordan", "review", "keep", "25.00");
    var oversized = new byte[Photos.MAX_UPLOAD_BYTES + 1];

    // act
    var response =
        createImportRowPhotoHandler.handleRequest(
            buildCreateEvent("jordan", importId, 1, oversized), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void createPhotoShouldRejectSixthPhoto() {
    // arrange
    var importId = seedImport("jordan", "review", "keep", "25.00");
    for (int i = 0; i < Photos.MAX_PHOTOS; i++) {
      var response =
          createImportRowPhotoHandler.handleRequest(
              buildCreateEvent("jordan", importId, 1, JPEG_BYTES), null);
      assertThat(response.getStatusCode()).isEqualTo(204);
    }

    // act
    var response =
        createImportRowPhotoHandler.handleRequest(
            buildCreateEvent("jordan", importId, 1, JPEG_BYTES), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void createPhotoShouldRejectNonKeepRow() {
    // arrange
    var importId = seedImport("jordan", "review", "discard", "25.00");

    // act
    var response =
        createImportRowPhotoHandler.handleRequest(
            buildCreateEvent("jordan", importId, 1, JPEG_BYTES), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void createPhotoShouldConflictWhenImportNotInReview() {
    // arrange
    var importId = seedImport("jordan", "appraising", "keep", "25.00");

    // act
    var response =
        createImportRowPhotoHandler.handleRequest(
            buildCreateEvent("jordan", importId, 1, JPEG_BYTES), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
    assertThat(response.getBody()).contains("import is not in review status");
  }

  @Test
  void createPhotoShouldReturn404ForUnknownImport() {
    // act
    var response =
        createImportRowPhotoHandler.handleRequest(
            buildCreateEvent("jordan", "missing", 1, JPEG_BYTES), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void createPhotoShouldReturn404ForUnknownRow() {
    // arrange
    var importId = seedImport("jordan", "review", "keep", "25.00");

    // act
    var response =
        createImportRowPhotoHandler.handleRequest(
            buildCreateEvent("jordan", importId, 99, JPEG_BYTES), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void deletePhotoShouldRemoveObjectAndPromoteNext() throws Exception {
    // arrange
    var importId = seedImport("jordan", "review", "keep", "25.00");
    createImportRowPhotoHandler.handleRequest(
        buildCreateEvent("jordan", importId, 1, JPEG_BYTES), null);
    createImportRowPhotoHandler.handleRequest(
        buildCreateEvent("jordan", importId, 1, JPEG_BYTES), null);

    // act
    var response =
        deleteImportRowPhotoHandler.handleRequest(
            buildDeleteEvent("jordan", importId, 1, "FAKE_ULID_0000000001"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);

    var rowItem = getRow("jordan", importId, 1);
    assertThat(rowItem.getPhotos()).hasSize(1);
    assertThat(rowItem.getPhotos().get(0).getPhotoId()).isEqualTo("FAKE_ULID_0000000002");

    var getResponse =
        getImportHandler.handleRequest(buildEvent("jordan", Map.of("import_id", importId)), null);
    assertThat(getResponse.getStatusCode()).isEqualTo(200);
    var photos = objectMapper.readTree(getResponse.getBody()).get("rows").get(0).get("photos");
    assertThat(photos).hasSize(1);
    assertThat(photos.get(0).get("photo_id").asText()).isEqualTo("FAKE_ULID_0000000002");
    assertThat(photos.get(0).get("url").asText())
        .contains(Photos.key("jordan", "FAKE_ULID_0000000002"));

    assertThatThrownBy(
            () ->
                s3Client.headObject(
                    r -> r.bucket(Photos.BUCKET).key(Photos.key("jordan", "FAKE_ULID_0000000001"))))
        .isInstanceOf(NoSuchKeyException.class);
  }

  @Test
  void deletePhotoShouldConflictWhenNotInReview() {
    // arrange
    var importId = seedImport("jordan", "review", "keep", "25.00");
    createImportRowPhotoHandler.handleRequest(
        buildCreateEvent("jordan", importId, 1, JPEG_BYTES), null);
    var importItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatImportSk(importId))
                .build());
    importItem.setStatus("confirmed");
    tcgInventoryTable.putItem(importItem);

    // act
    var response =
        deleteImportRowPhotoHandler.handleRequest(
            buildDeleteEvent("jordan", importId, 1, "FAKE_ULID_0000000001"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
  }

  @Test
  void deletePhotoShouldReturn404ForUnknownPhoto() {
    // arrange
    var importId = seedImport("jordan", "review", "keep", "25.00");

    // act
    var response =
        deleteImportRowPhotoHandler.handleRequest(
            buildDeleteEvent("jordan", importId, 1, "missing"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void getImportShouldFlagNeedsPhotosWhenKeepAtGateWithNoPhotos() throws Exception {
    // arrange
    var importId = seedImport("jordan", "review", "keep", "20.00");

    // act
    var response =
        getImportHandler.handleRequest(buildEvent("jordan", Map.of("import_id", importId)), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var row = objectMapper.readTree(response.getBody()).get("rows").get(0);
    assertThat(row.get("needs_photos").asBoolean()).isTrue();
    assertThat(row.get("photos")).isEmpty();
  }

  @Test
  void getImportShouldClearNeedsPhotosAfterAdd() throws Exception {
    // arrange
    var importId = seedImport("jordan", "review", "keep", "20.00");
    createImportRowPhotoHandler.handleRequest(
        buildCreateEvent("jordan", importId, 1, JPEG_BYTES), null);

    // act
    var response =
        getImportHandler.handleRequest(buildEvent("jordan", Map.of("import_id", importId)), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var row = objectMapper.readTree(response.getBody()).get("rows").get(0);
    assertThat(row.get("needs_photos").asBoolean()).isFalse();
    assertThat(row.get("photos")).hasSize(1);
    assertThat(row.get("photos").get(0).get("photo_id").asText()).isEqualTo("FAKE_ULID_0000000001");
    assertThat(row.get("photos").get(0).get("url").asText())
        .contains(Photos.key("jordan", "FAKE_ULID_0000000001"));
  }

  @Test
  void getImportShouldNotFlagBelowGateOrNonKeep() throws Exception {
    // arrange
    seedImport("jordan", "review", "keep", "19.99");
    var discardId = "import2";
    var discardImport =
        TcgInventoryItem.createImport(
            "jordan", discardId, "test.csv", 1, null, Instant.ofEpochSecond(1700000000));
    discardImport.setStatus("review");
    tcgInventoryTable.putItem(discardImport);
    var discardRow =
        TcgInventoryItem.createImportRow(
            "jordan",
            discardId,
            1,
            "Bulk",
            "dom",
            "Dominaria",
            "1",
            "normal",
            "NM",
            "scryfall-2",
            "en");
    discardRow.setDecision("discard");
    discardRow.setSuggestedPrice("50.00");
    tcgInventoryTable.putItem(discardRow);

    // act
    var belowGate =
        getImportHandler.handleRequest(buildEvent("jordan", Map.of("import_id", "import1")), null);
    var discard =
        getImportHandler.handleRequest(buildEvent("jordan", Map.of("import_id", discardId)), null);

    // assert
    assertThat(
            objectMapper
                .readTree(belowGate.getBody())
                .get("rows")
                .get(0)
                .get("needs_photos")
                .asBoolean())
        .isFalse();
    assertThat(
            objectMapper
                .readTree(discard.getBody())
                .get("rows")
                .get(0)
                .get("needs_photos")
                .asBoolean())
        .isFalse();
  }

  private String seedImport(String user, String status, String decision, String suggestedPrice) {
    var importId = "import1";
    var importItem =
        TcgInventoryItem.createImport(
            user, importId, "test.csv", 1, null, Instant.ofEpochSecond(1700000000));
    importItem.setStatus(status);
    tcgInventoryTable.putItem(importItem);

    var rowItem =
        TcgInventoryItem.createImportRow(
            user,
            importId,
            1,
            "Llanowar Elves",
            "dom",
            "Dominaria",
            "168",
            "normal",
            "NM",
            "scryfall-1",
            "en");
    rowItem.setDecision(decision);
    rowItem.setSuggestedPrice(suggestedPrice);
    tcgInventoryTable.putItem(rowItem);
    return importId;
  }

  private TcgInventoryItem getRow(String user, String importId, int position) {
    return tcgInventoryTable.getItem(
        Key.builder()
            .partitionValue(TcgInventoryItem.formatImportRowPk(user, importId))
            .sortValue(TcgInventoryItem.formatImportRowSk(position))
            .build());
  }

  private APIGatewayV2HTTPEvent buildCreateEvent(
      String user, String importId, int position, byte[] body) {
    return buildCreateEvent(user, importId, position, body, "image/jpeg");
  }

  private APIGatewayV2HTTPEvent buildCreateEvent(
      String user, String importId, int position, byte[] body, String contentType) {
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader(user), "content-type", contentType))
        .withPathParameters(Map.of("import_id", importId, "position", Integer.toString(position)))
        .withBody(Base64.getEncoder().encodeToString(body))
        .withIsBase64Encoded(true)
        .build();
  }

  private APIGatewayV2HTTPEvent buildDeleteEvent(
      String user, String importId, int position, String photoId) {
    return buildEvent(
        user,
        Map.of("import_id", importId, "position", Integer.toString(position), "photo_id", photoId));
  }

  private APIGatewayV2HTTPEvent buildEvent(String user, Map<String, String> pathParams) {
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader(user)))
        .withPathParameters(pathParams)
        .build();
  }

  private String authHeader(String user) {
    return "Basic "
        + Base64.getEncoder().encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
  }
}
