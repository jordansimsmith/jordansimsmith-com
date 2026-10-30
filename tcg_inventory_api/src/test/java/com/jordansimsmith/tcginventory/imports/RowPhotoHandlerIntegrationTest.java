package com.jordansimsmith.tcginventory.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.s3.S3Container;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.photos.client.GetPhotoBytesRequest;
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

@Testcontainers
public class RowPhotoHandlerIntegrationTest {
  private static final byte[] JPEG_BYTES =
      new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};

  private ObjectMapper objectMapper;
  private DynamoDbTable<ImportItem> importTable;
  private DynamoDbTable<ImportRowItem> importRowTable;
  private TcgInventoryTestFactory factory;

  private CreateImportRowPhotoHandler createImportRowPhotoHandler;
  private DeleteImportRowPhotoHandler deleteImportRowPhotoHandler;
  private GetImportHandler getImportHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();
  @Container private static final S3Container s3Container = new S3Container();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
  }

  @BeforeEach
  void setUp() {
    factory =
        TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint(), s3Container.getEndpoint());

    objectMapper = factory.objectMapper();
    importTable = factory.importTable();
    importRowTable = factory.importRowTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());

    createImportRowPhotoHandler = new CreateImportRowPhotoHandler(factory);
    deleteImportRowPhotoHandler = new DeleteImportRowPhotoHandler(factory);
    getImportHandler = new GetImportHandler(factory);
  }

  @Test
  void createPhotoShouldStoreThroughPhotosAndReturnNoContent() throws Exception {
    // arrange
    var importId = seedImport("jordan", "review", "keep", "25.00");

    // act
    var response =
        createImportRowPhotoHandler.handleRequest(
            buildCreateEvent("jordan", importId, 1, JPEG_BYTES), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);

    var rowItem = getRow("jordan", importId, 1);
    assertThat(rowItem.getPhotos()).hasSize(1);
    var photoId = rowItem.getPhotos().get(0).getPhotoId();
    assertThat(photoId).isEqualTo("FAKE_PHOTO_0000000001");
    assertThat(
            factory
                .photosService()
                .getPhotoBytes(new RequestContext("jordan"), new GetPhotoBytesRequest(photoId))
                .photoBytes())
        .isEqualTo(JPEG_BYTES);
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
  void createPhotoShouldRejectSixthPhoto() {
    // arrange
    var importId = seedImport("jordan", "review", "keep", "25.00");
    for (int i = 0; i < ImportPhotos.MAX_PHOTOS; i++) {
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
            buildDeleteEvent("jordan", importId, 1, "FAKE_PHOTO_0000000001"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);

    var rowItem = getRow("jordan", importId, 1);
    assertThat(rowItem.getPhotos()).hasSize(1);
    assertThat(rowItem.getPhotos().get(0).getPhotoId()).isEqualTo("FAKE_PHOTO_0000000002");

    var getResponse =
        getImportHandler.handleRequest(buildEvent("jordan", Map.of("import_id", importId)), null);
    assertThat(getResponse.getStatusCode()).isEqualTo(200);
    var photos = objectMapper.readTree(getResponse.getBody()).get("rows").get(0).get("photos");
    assertThat(photos).hasSize(1);
    assertThat(photos.get(0).asText()).isEqualTo("FAKE_PHOTO_0000000002");
  }

  @Test
  void deletePhotoShouldConflictWhenNotInReview() {
    // arrange
    var importId = seedImport("jordan", "review", "keep", "25.00");
    createImportRowPhotoHandler.handleRequest(
        buildCreateEvent("jordan", importId, 1, JPEG_BYTES), null);
    var importItem =
        importTable.getItem(
            Key.builder()
                .partitionValue(SkuItem.formatUserPk("jordan"))
                .sortValue(ImportItem.formatSk(importId))
                .build());
    importItem.setStatus("confirmed");
    importTable.putItem(importItem);

    // act
    var response =
        deleteImportRowPhotoHandler.handleRequest(
            buildDeleteEvent("jordan", importId, 1, "FAKE_PHOTO_0000000001"), null);

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
  void getImportShouldReturnPhotoIdsAndClearNeedsPhotosAfterAdd() throws Exception {
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
    assertThat(row.get("photos").get(0).asText()).isEqualTo("FAKE_PHOTO_0000000001");
  }

  @Test
  void getImportShouldNotFlagBelowGateOrNonKeep() throws Exception {
    // arrange
    seedImport("jordan", "review", "keep", "19.99");
    var discardId = "import2";
    var discardImport =
        ImportItem.create(
            "jordan", "mtg", discardId, "test.csv", 1, null, Instant.ofEpochSecond(1700000000));
    discardImport.setStatus("review");
    importTable.putItem(discardImport);
    var discardRow =
        ImportRowItem.create(
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
    importRowTable.putItem(discardRow);

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
        ImportItem.create(
            user, "mtg", importId, "test.csv", 1, null, Instant.ofEpochSecond(1700000000));
    importItem.setStatus(status);
    importTable.putItem(importItem);

    var rowItem =
        ImportRowItem.create(
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
    importRowTable.putItem(rowItem);
    return importId;
  }

  private ImportRowItem getRow(String user, String importId, int position) {
    return importRowTable.getItem(
        Key.builder()
            .partitionValue(ImportRowItem.formatPk(user, importId))
            .sortValue(ImportRowItem.formatSk(position))
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
