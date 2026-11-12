package com.jordansimsmith.tcginventory.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.imports.api.DeleteImportHandler;
import com.jordansimsmith.tcginventory.imports.api.DeleteImportRowHandler;
import com.jordansimsmith.tcginventory.imports.api.FindImportsHandler;
import com.jordansimsmith.tcginventory.imports.api.GetImportHandler;
import com.jordansimsmith.tcginventory.imports.api.UpdateImportRowHandler;
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
public class ImportsHandlerIntegrationTest {
  private ObjectMapper objectMapper;
  private DynamoDbTable<ImportItem> importTable;
  private DynamoDbTable<ImportRowItem> importRowTable;
  private GetImportHandler getImportHandler;
  private FindImportsHandler findImportsHandler;
  private DeleteImportHandler deleteImportHandler;
  private UpdateImportRowHandler updateImportRowHandler;
  private DeleteImportRowHandler deleteImportRowHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    var table = factory.tableDefinition();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);

    objectMapper = factory.objectMapper();
    importTable = factory.importTable();
    importRowTable = factory.importRowTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    getImportHandler = new GetImportHandler(factory);
    findImportsHandler = new FindImportsHandler(factory);
    deleteImportHandler = new DeleteImportHandler(factory);
    updateImportRowHandler = new UpdateImportRowHandler(factory);
    deleteImportRowHandler = new DeleteImportRowHandler(factory);
  }

  @Test
  void getImportShouldReturnDetailWithRows() throws Exception {
    // arrange
    var importId = createImportWithUndecidedRow("jordan");

    // act
    var response =
        getImportHandler.handleRequest(
            buildEventWithPathParam("jordan", Map.of("import_id", importId)), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("import_id").asText()).isEqualTo(importId);
    assertThat(body.get("game").asText()).isEqualTo("mtg");
    assertThat(body.get("filename").asText()).isEqualTo("test.csv");
    assertThat(body.get("rows")).hasSize(1);

    var row = body.get("rows").get(0);
    assertThat(row.get("position").asInt()).isEqualTo(1);
    assertThat(row.get("name").asText()).isEqualTo("Llanowar Elves");
    assertThat(row.get("set_code").asText()).isEqualTo("dom");
    assertThat(row.get("finish").asText()).isEqualTo("normal");
    assertThat(row.get("condition").asText()).isEqualTo("NM");
    assertThat(row.has("game")).isFalse();
    assertThat(row.has("external_source")).isFalse();
    assertThat(row.get("external_id").asText()).isEqualTo("opaque/card");
    assertThat(row.get("decision").isNull()).isTrue();
    assertThat(row.get("decision_reason").isNull()).isTrue();
    assertThat(body.get("total_suggested_price").asText()).isEqualTo("0.00");
  }

  @Test
  void getImportShouldReturnTotalSuggestedPriceFromKeepRows() throws Exception {
    // arrange
    var importId = createReviewImportWithRow("jordan");
    var discard =
        ImportRowItem.create(
            "jordan",
            importId,
            2,
            "Opt",
            "dom",
            "Dominaria",
            "60",
            "normal",
            "NM",
            "scryfall-2",
            "en");
    discard.setDecision("discard");
    importRowTable.putItem(discard);
    var keepB =
        ImportRowItem.create(
            "jordan",
            importId,
            3,
            "Sol Ring",
            "cmr",
            "Commander Legends",
            "472",
            "normal",
            "NM",
            "scryfall-3",
            "en");
    keepB.setDecision("keep");
    keepB.setSuggestedPrice("3.10");
    importRowTable.putItem(keepB);

    // act
    var response =
        getImportHandler.handleRequest(
            buildEventWithPathParam("jordan", Map.of("import_id", importId)), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("total_suggested_price").asText()).isEqualTo("4.50");
  }

  @Test
  void getImportShouldReturn404ForUnknown() {
    // act
    var response =
        getImportHandler.handleRequest(
            buildEventWithPathParam("jordan", Map.of("import_id", "nonexistent")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void deleteImportShouldRemoveImportAndRows() throws Exception {
    // arrange
    var importId = createReviewImportWithRow("jordan");

    // act
    var response =
        deleteImportHandler.handleRequest(
            buildEventWithPathParam("jordan", Map.of("import_id", importId)), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);

    assertThat(
            getImportHandler
                .handleRequest(
                    buildEventWithPathParam("jordan", Map.of("import_id", importId)), null)
                .getStatusCode())
        .isEqualTo(404);
    assertThat(
            findImportsHandler
                .handleRequest(buildEventWithPathParam("jordan", Map.of()), null)
                .getBody())
        .doesNotContain(importId);
  }

  private APIGatewayV2HTTPEvent buildEventWithPathParam(
      String user, Map<String, String> pathParams) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withPathParameters(pathParams)
        .build();
  }

  private APIGatewayV2HTTPEvent buildEventWithBody(
      String user, Map<String, String> pathParams, String body) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader))
        .withPathParameters(pathParams)
        .withBody(body)
        .build();
  }

  private String createImportWithUndecidedRow(String user) {
    var importId = "import-detail";
    importTable.putItem(
        ImportItem.create(
            user, "mtg", importId, "test.csv", 1, null, Instant.ofEpochSecond(1700000000)));
    importRowTable.putItem(
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
            "opaque/card",
            "en"));
    return importId;
  }

  private String createReviewImportWithRow(String user) {
    var importId = "import1";
    var importItem =
        ImportItem.create(
            user, "mtg", importId, "test.csv", 1, null, Instant.ofEpochSecond(1700000000));
    importItem.setStatus("review");
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
    rowItem.setDecision("keep");
    rowItem.setMarketPrice("1.50");
    rowItem.setSuggestedPrice("1.40");
    importRowTable.putItem(rowItem);

    return importId;
  }

  @Test
  void updateImportRowShouldUpdateCondition() throws Exception {
    // arrange
    var importId = createReviewImportWithRow("jordan");

    // act
    var response =
        updateImportRowHandler.handleRequest(
            buildEventWithBody(
                "jordan", Map.of("import_id", importId, "position", "1"), "{\"condition\":\"LP\"}"),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);
    assertThat(response.getBody()).isNull();

    var rowItem =
        importRowTable.getItem(
            Key.builder()
                .partitionValue(ImportRowItem.formatPk("jordan", importId))
                .sortValue(ImportRowItem.formatSk(1))
                .build());
    assertThat(rowItem.getCondition()).isEqualTo("LP");
  }

  @Test
  void updateImportRowShouldReturn409WhenNotInReview() {
    // arrange
    var importId = createReviewImportWithRow("jordan");
    var importItem =
        importTable.getItem(
            Key.builder()
                .partitionValue(ImportItem.formatPk("jordan"))
                .sortValue(ImportItem.formatSk(importId))
                .build());
    importItem.setStatus("appraising");
    importTable.putItem(importItem);

    // act
    var response =
        updateImportRowHandler.handleRequest(
            buildEventWithBody(
                "jordan", Map.of("import_id", importId, "position", "1"), "{\"condition\":\"LP\"}"),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
  }

  @Test
  void updateImportRowShouldReturn404ForMissingRow() {
    // arrange
    createReviewImportWithRow("jordan");

    // act
    var response =
        updateImportRowHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                Map.of("import_id", "import1", "position", "99"),
                "{\"condition\":\"LP\"}"),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void updateImportRowShouldReturn400ForInvalidCondition() {
    // arrange
    createReviewImportWithRow("jordan");

    // act
    var response =
        updateImportRowHandler.handleRequest(
            buildEventWithBody(
                "jordan",
                Map.of("import_id", "import1", "position", "1"),
                "{\"condition\":\"INVALID\"}"),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void deleteImportRowShouldDeleteRowAndPreserveOriginalCount() throws Exception {
    // arrange
    createReviewImportWithRow("jordan");

    // act
    var response =
        deleteImportRowHandler.handleRequest(
            buildEventWithPathParam("jordan", Map.of("import_id", "import1", "position", "1")),
            null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);

    var rowItem =
        importRowTable.getItem(
            Key.builder()
                .partitionValue(ImportRowItem.formatPk("jordan", "import1"))
                .sortValue(ImportRowItem.formatSk(1))
                .build());
    assertThat(rowItem).isNull();
    var savedImport =
        importTable.getItem(
            Key.builder()
                .partitionValue(ImportItem.formatPk("jordan"))
                .sortValue(ImportItem.formatSk("import1"))
                .build());
    assertThat(savedImport.getRowCount()).isEqualTo(1);
    var detailResponse =
        getImportHandler.handleRequest(
            buildEventWithPathParam("jordan", Map.of("import_id", "import1")), null);
    var detail = objectMapper.readTree(detailResponse.getBody());
    assertThat(detail.get("row_count").asInt()).isEqualTo(1);
    assertThat(detail.get("rows")).isEmpty();
    assertThat(detail.get("total_suggested_price").asText()).isEqualTo("0.00");
  }
}
