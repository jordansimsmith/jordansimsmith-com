package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.time.FakeClock;
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
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

@Testcontainers
public class ImportsHandlerIntegrationTest {
  private static final String CSV_HEADER =
      "Name,Set code,Set name,Collector number,Foil,Rarity,Quantity,Scryfall"
          + " ID,Misprint,Altered,Condition,Language";

  private FakeClock fakeClock;
  private FakeUlidGenerator fakeUlidGenerator;
  private FakeQueueClient<JobMessage> fakeJobsQueue;
  private ObjectMapper objectMapper;
  private DynamoDbTable<TcgInventoryItem> tcgInventoryTable;

  private CreateImportHandler createImportHandler;
  private FindImportsHandler findImportsHandler;
  private GetImportHandler getImportHandler;
  private DeleteImportHandler deleteImportHandler;
  private UpdateImportRowHandler updateImportRowHandler;

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  @BeforeAll
  static void setUpBeforeClass() {
    var factory = TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint());
    var table = factory.tcgInventoryTable();
    DynamoDbUtils.createTable(factory.dynamoDbClient(), table);
  }

  @BeforeEach
  void setUp() {
    var factory = TcgInventoryTestFactory.create(dynamoDbContainer.getEndpoint());

    fakeClock = factory.fakeClock();
    fakeUlidGenerator = factory.fakeUlidGenerator();
    fakeJobsQueue = factory.fakeJobsQueue();
    objectMapper = factory.objectMapper();
    tcgInventoryTable = factory.tcgInventoryTable();

    DynamoDbUtils.reset(factory.dynamoDbClient());
    fakeUlidGenerator.reset();
    fakeJobsQueue.reset();

    createImportHandler = new CreateImportHandler(factory);
    findImportsHandler = new FindImportsHandler(factory);
    getImportHandler = new GetImportHandler(factory);
    deleteImportHandler = new DeleteImportHandler(factory);
    updateImportRowHandler = new UpdateImportRowHandler(factory);
  }

  @Test
  void createImportShouldPersistImportAndRows() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var csv =
        CSV_HEADER
            + "\n"
            + "Llanowar"
            + " Elves,DOM,Dominaria,168,Normal,Common,1,581b7327-3215-4a4f-b4ae-d9d4002ba882,false,false,near_mint,en\n"
            + "Sol Ring,CMR,Commander Legends,472,Normal,Mythic"
            + " Rare,1,58b26011-e103-45c4-a253-900f4e6b2eeb,false,false,mint,en";
    var event = buildCreateEvent("jordan", csv, "manabox-export.csv");

    // act
    var response = createImportHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);

    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("import_id").asText()).isNotEmpty();
    assertThat(body.get("filename").asText()).isEqualTo("manabox-export.csv");
    assertThat(body.get("status").asText()).isEqualTo("appraising");
    assertThat(body.get("row_count").asInt()).isEqualTo(2);
    assertThat(body.get("keep_count").asInt()).isZero();
    assertThat(body.get("discard_count").asInt()).isZero();
    assertThat(body.get("review_count").asInt()).isZero();
    assertThat(body.get("appraisal_error").isNull()).isTrue();
    assertThat(body.get("created_at").asLong()).isEqualTo(1700000000);

    var importId = body.get("import_id").asText();

    // verify rows in DynamoDB
    var rowQuery =
        QueryConditional.sortBeginsWith(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatImportRowPk("jordan", importId))
                .sortValue(TcgInventoryItem.ROW_PREFIX)
                .build());
    var rows =
        tcgInventoryTable
            .query(QueryEnhancedRequest.builder().queryConditional(rowQuery).build())
            .stream()
            .flatMap(page -> page.items().stream())
            .toList();
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).getPosition()).isEqualTo(1);
    assertThat(rows.get(0).getName()).isEqualTo("Sol Ring");
    assertThat(rows.get(1).getPosition()).isEqualTo(2);
    assertThat(rows.get(1).getName()).isEqualTo("Llanowar Elves");

    // verify job item in DynamoDB
    var importItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatImportSk(importId))
                .build());
    assertThat(importItem.getJobId()).isNotEmpty();

    var jobItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatJobSk(importItem.getJobId()))
                .build());
    assertThat(jobItem).isNotNull();
    assertThat(jobItem.getJobType()).isEqualTo("appraise");
    assertThat(jobItem.getStatus()).isEqualTo("queued");
    assertThat(jobItem.getImportId()).isEqualTo(importId);

    // verify SQS enqueue
    assertThat(fakeJobsQueue.getMessages()).hasSize(1);
    assertThat(fakeJobsQueue.getMessages().get(0).jobType()).isEqualTo("appraise");
  }

  @Test
  void createImportShouldExpandQuantities() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var csv =
        CSV_HEADER
            + "\n"
            + "Sol Ring,CMR,Commander Legends,472,Normal,Mythic"
            + " Rare,3,58b26011-e103-45c4-a253-900f4e6b2eeb,false,false,mint,en";
    var event = buildCreateEvent("jordan", csv, "export.csv");

    // act
    var response = createImportHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("row_count").asInt()).isEqualTo(3);
  }

  @Test
  void createImportShouldRejectMalformedCsv() {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var csv = "Wrong,Headers,Only\ndata,here,now";
    var event = buildCreateEvent("jordan", csv, "bad.csv");

    // act
    var response = createImportHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void createImportShouldRejectEmptyBody() {
    // arrange
    var event = buildCreateEvent("jordan", "", "empty.csv");

    // act
    var response = createImportHandler.handleRequest(event, null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void findImportsShouldReturnNewestFirst() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    createImportHandler.handleRequest(
        buildCreateEvent("jordan", buildSingleCardCsv(), "first.csv"), null);

    fakeClock.setTime(Instant.ofEpochSecond(1700001000));
    createImportHandler.handleRequest(
        buildCreateEvent("jordan", buildSingleCardCsv(), "second.csv"), null);

    // act
    var response = findImportsHandler.handleRequest(buildEvent("jordan"), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    var imports = body.get("imports");
    assertThat(imports).hasSize(2);
    assertThat(imports.get(0).get("filename").asText()).isEqualTo("second.csv");
    assertThat(imports.get(1).get("filename").asText()).isEqualTo("first.csv");
  }

  @Test
  void getImportShouldReturnDetailWithRows() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var csv =
        CSV_HEADER
            + "\n"
            + "Llanowar"
            + " Elves,DOM,Dominaria,168,Normal,Common,1,581b7327-3215-4a4f-b4ae-d9d4002ba882,false,false,near_mint,en";
    var createResponse =
        createImportHandler.handleRequest(buildCreateEvent("jordan", csv, "test.csv"), null);
    var importId = objectMapper.readTree(createResponse.getBody()).get("import_id").asText();

    // act
    var response =
        getImportHandler.handleRequest(
            buildEventWithPathParam("jordan", Map.of("import_id", importId)), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("import_id").asText()).isEqualTo(importId);
    assertThat(body.get("filename").asText()).isEqualTo("test.csv");
    assertThat(body.get("rows")).hasSize(1);

    var row = body.get("rows").get(0);
    assertThat(row.get("position").asInt()).isEqualTo(1);
    assertThat(row.get("name").asText()).isEqualTo("Llanowar Elves");
    assertThat(row.get("set_code").asText()).isEqualTo("dom");
    assertThat(row.get("finish").asText()).isEqualTo("normal");
    assertThat(row.get("condition").asText()).isEqualTo("NM");
    assertThat(row.get("scryfall_id").asText()).isEqualTo("581b7327-3215-4a4f-b4ae-d9d4002ba882");
    assertThat(row.get("decision").isNull()).isTrue();
    assertThat(row.get("decision_reason").isNull()).isTrue();
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
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var createResponse =
        createImportHandler.handleRequest(
            buildCreateEvent("jordan", buildSingleCardCsv(), "test.csv"), null);
    var importId = objectMapper.readTree(createResponse.getBody()).get("import_id").asText();

    // simulate review status (deletion only allowed in review)
    var importItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatImportSk(importId))
                .build());
    importItem.setStatus("review");
    tcgInventoryTable.putItem(importItem);

    // act
    var response =
        deleteImportHandler.handleRequest(
            buildEventWithPathParam("jordan", Map.of("import_id", importId)), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(204);

    var deletedImport =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatImportSk(importId))
                .build());
    assertThat(deletedImport).isNull();

    var rowQuery =
        QueryConditional.sortBeginsWith(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatImportRowPk("jordan", importId))
                .sortValue(TcgInventoryItem.ROW_PREFIX)
                .build());
    var rows =
        tcgInventoryTable
            .query(QueryEnhancedRequest.builder().queryConditional(rowQuery).build())
            .stream()
            .flatMap(page -> page.items().stream())
            .toList();
    assertThat(rows).isEmpty();
  }

  @Test
  void deleteImportShouldReturn409WhenAppraising() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var createResponse =
        createImportHandler.handleRequest(
            buildCreateEvent("jordan", buildSingleCardCsv(), "test.csv"), null);
    var importId = objectMapper.readTree(createResponse.getBody()).get("import_id").asText();

    // act (import is still in appraising status from creation)
    var response =
        deleteImportHandler.handleRequest(
            buildEventWithPathParam("jordan", Map.of("import_id", importId)), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
  }

  @Test
  void deleteImportShouldReturn409AfterConfirm() throws Exception {
    // arrange
    fakeClock.setTime(Instant.ofEpochSecond(1700000000));
    var createResponse =
        createImportHandler.handleRequest(
            buildCreateEvent("jordan", buildSingleCardCsv(), "test.csv"), null);
    var importId = objectMapper.readTree(createResponse.getBody()).get("import_id").asText();

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
        deleteImportHandler.handleRequest(
            buildEventWithPathParam("jordan", Map.of("import_id", importId)), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(409);
  }

  @Test
  void deleteImportShouldReturn404ForUnknown() {
    // act
    var response =
        deleteImportHandler.handleRequest(
            buildEventWithPathParam("jordan", Map.of("import_id", "nonexistent")), null);

    // assert
    assertThat(response.getStatusCode()).isEqualTo(404);
  }

  private String buildSingleCardCsv() {
    return CSV_HEADER
        + "\n"
        + "Llanowar"
        + " Elves,DOM,Dominaria,168,Normal,Common,1,581b7327-3215-4a4f-b4ae-d9d4002ba882,false,false,near_mint,en";
  }

  private APIGatewayV2HTTPEvent buildCreateEvent(String user, String body, String filename) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder()
        .withHeaders(Map.of("Authorization", authHeader, "content-type", "text/csv"))
        .withQueryStringParameters(Map.of("filename", filename))
        .withBody(body)
        .build();
  }

  private APIGatewayV2HTTPEvent buildEvent(String user) {
    var authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((user + ":password").getBytes(StandardCharsets.UTF_8));
    return APIGatewayV2HTTPEvent.builder().withHeaders(Map.of("Authorization", authHeader)).build();
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

  private String createReviewImportWithRow(String user) {
    var importId = "import1";
    var importItem = new TcgInventoryItem();
    importItem.setPk(TcgInventoryItem.formatUserPk(user));
    importItem.setSk(TcgInventoryItem.formatImportSk(importId));
    importItem.setImportId(importId);
    importItem.setFilename("test.csv");
    importItem.setStatus("review");
    importItem.setRowCount(1);
    importItem.setKeepCount(1);
    importItem.setDiscardCount(0);
    importItem.setReviewCount(0);
    importItem.setCreatedAt(Instant.ofEpochSecond(1700000000));
    tcgInventoryTable.putItem(importItem);

    var rowItem = new TcgInventoryItem();
    rowItem.setPk(TcgInventoryItem.formatImportRowPk(user, importId));
    rowItem.setSk(TcgInventoryItem.formatImportRowSk(1));
    rowItem.setPosition(1);
    rowItem.setName("Llanowar Elves");
    rowItem.setSetCode("dom");
    rowItem.setSetName("Dominaria");
    rowItem.setCollectorNumber("168");
    rowItem.setFinish("normal");
    rowItem.setCondition("NM");
    rowItem.setScryfallId("scryfall-1");
    rowItem.setLanguage("en");
    rowItem.setDecision("keep");
    rowItem.setMarketPrice("1.50");
    rowItem.setSuggestedPrice("1.40");
    tcgInventoryTable.putItem(rowItem);

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
    assertThat(response.getStatusCode()).isEqualTo(200);
    var body = objectMapper.readTree(response.getBody());
    assertThat(body.get("condition").asText()).isEqualTo("LP");
    assertThat(body.get("name").asText()).isEqualTo("Llanowar Elves");
    assertThat(body.get("decision").asText()).isEqualTo("keep");
    assertThat(body.get("market_price").asText()).isEqualTo("1.50");

    var rowItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatImportRowPk("jordan", importId))
                .sortValue(TcgInventoryItem.formatImportRowSk(1))
                .build());
    assertThat(rowItem.getCondition()).isEqualTo("LP");
  }

  @Test
  void updateImportRowShouldReturn409WhenNotInReview() {
    // arrange
    var importId = createReviewImportWithRow("jordan");
    var importItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(TcgInventoryItem.formatUserPk("jordan"))
                .sortValue(TcgInventoryItem.formatImportSk(importId))
                .build());
    importItem.setStatus("appraising");
    tcgInventoryTable.putItem(importItem);

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
}
