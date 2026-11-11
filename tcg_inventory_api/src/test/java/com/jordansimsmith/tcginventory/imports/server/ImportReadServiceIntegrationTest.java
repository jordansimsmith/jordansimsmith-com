package com.jordansimsmith.tcginventory.imports.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.imports.client.FindImportsRequest;
import com.jordansimsmith.tcginventory.imports.client.GetImportRequest;
import com.jordansimsmith.tcginventory.imports.client.ImportDecision;
import com.jordansimsmith.tcginventory.imports.client.ImportRow;
import com.jordansimsmith.tcginventory.imports.client.ImportStatus;
import com.jordansimsmith.tcginventory.imports.client.ImportSummary;
import com.jordansimsmith.tcginventory.imports.client.PlacementInstruction;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Testcontainers
class ImportReadServiceIntegrationTest {
  private static final String TABLE_NAME = "imports_test";
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<ImportItem> importTable;
  private static DynamoDbTable<ImportRowItem> importRowTable;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private FindImportsService findImportsService;
  private GetImportService getImportService;

  @BeforeAll
  static void setUpBeforeClass() {
    dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    importTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(ImportItem.class));
    importRowTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(ImportRowItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, importTable);
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    findImportsService =
        new FindImportsService(new ImportReadRepository(importTable, importRowTable), objectMapper);
    getImportService = new GetImportService(new ImportReadRepository(importTable, importRowTable));
  }

  @Test
  void findImportsReturnsTypedSummariesNewestFirstAndOnlyOnePage() throws Exception {
    importTable.putItem(item("alice", "1", "first.csv", "review", 2, 1_700_000_001L));
    importTable.putItem(item("alice", "2", null, "confirmed", 5, 1_700_000_002L));
    importTable.putItem(item("alice", "3", "third.csv", "appraising", 1, 1_700_000_003L));
    importTable.putItem(item("bob", "4", "other.csv", "review", 9, 1_700_000_004L));

    var firstPage =
        findImportsService.findImports(
            new RequestContext("alice"), new FindImportsRequest(2, null));
    var secondPage =
        findImportsService.findImports(
            new RequestContext("alice"), new FindImportsRequest(2, firstPage.nextContinuation()));

    assertThat(firstPage.imports()).extracting(ImportSummary::importId).containsExactly("3", "2");
    assertThat(firstPage.imports().get(0).status()).isEqualTo(ImportStatus.APPRAISING);
    assertThat(firstPage.imports().get(0).createdAt()).isEqualTo(1_700_000_003L);
    assertThat(firstPage.imports().get(1).filename()).isNull();
    assertThat(firstPage.nextContinuation()).isNotBlank();
    assertThat(secondPage.imports()).extracting(ImportSummary::importId).containsExactly("1");
    assertThat(secondPage.nextContinuation()).isNull();

    var storedItem =
        dynamoDbClient
            .getItem(
                request ->
                    request
                        .tableName(TABLE_NAME)
                        .key(
                            Map.of(
                                ImportItem.PK, AttributeValue.fromS("USER#alice"),
                                ImportItem.SK, AttributeValue.fromS("IMPORT#3"))))
            .item();
    assertThat(storedItem)
        .containsEntry(ImportItem.PK, AttributeValue.fromS("USER#alice"))
        .containsEntry(ImportItem.SK, AttributeValue.fromS("IMPORT#3"))
        .containsEntry(ImportItem.CREATED_AT, AttributeValue.fromN("1700000003"));

    var json = objectMapper.valueToTree(firstPage);
    assertThat(json.get("imports").get(0).get("status").asText()).isEqualTo("appraising");
    assertThat(json.get("imports").get(0).has("appraisal_error")).isFalse();
  }

  @Test
  void findImportsReturnsEmptyListForUserWithoutImports() throws Exception {
    importTable.putItem(item("alice", "1", "first.csv", "review", 2, 1_700_000_001L));

    var response =
        findImportsService.findImports(
            new RequestContext("bob"), new FindImportsRequest(null, null));

    assertThat(response.imports()).isEmpty();
    assertThat(response.nextContinuation()).isNull();
  }

  @Test
  void legacyErrorAttributeIsNotProjectedIntoSummary() throws Exception {
    dynamoDbClient.putItem(
        request ->
            request
                .tableName(TABLE_NAME)
                .item(
                    Map.of(
                        ImportItem.PK,
                        AttributeValue.fromS("USER#alice"),
                        ImportItem.SK,
                        AttributeValue.fromS("IMPORT#legacy"),
                        ImportItem.IMPORT_ID,
                        AttributeValue.fromS("legacy"),
                        ImportItem.GAME,
                        AttributeValue.fromS("mtg"),
                        ImportItem.STATUS,
                        AttributeValue.fromS("appraising"),
                        ImportItem.ROW_COUNT,
                        AttributeValue.fromN("1"),
                        ImportItem.CREATED_AT,
                        AttributeValue.fromN("1700000000"),
                        "error",
                        AttributeValue.fromS("old processing error"))));

    var response =
        findImportsService.findImports(
            new RequestContext("alice"), new FindImportsRequest(null, null));

    var json = objectMapper.valueToTree(response);
    assertThat(json.get("imports").get(0).has("appraisal_error")).isFalse();
    assertThat(json.get("imports").get(0).has("error")).isFalse();
  }

  @Test
  void invalidLimitsAndContinuationAreRejectedBeforeRepositoryAccess() throws Exception {
    var repository = mock(ImportReadRepository.class);
    var service = new FindImportsService(repository, objectMapper);

    assertThatThrownBy(
            () -> service.findImports(new RequestContext("alice"), new FindImportsRequest(0, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("limit must be between 1 and 100");
    assertThatThrownBy(
            () ->
                service.findImports(new RequestContext("alice"), new FindImportsRequest(101, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("limit must be between 1 and 100");
    assertThatThrownBy(
            () ->
                service.findImports(
                    new RequestContext("alice"), new FindImportsRequest(null, "not-a-token")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("Invalid continuation");
    verifyNoInteractions(repository);
  }

  @Test
  void unknownStoredStatusFailsVisibly() {
    importTable.putItem(item("alice", "1", "first.csv", "failed", 2, 1_700_000_001L));

    assertThatThrownBy(
            () ->
                findImportsService.findImports(
                    new RequestContext("alice"), new FindImportsRequest(null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void repositoryStorageFailurePropagatesUnchanged() throws Exception {
    var repository = mock(ImportReadRepository.class);
    var failure = new IllegalStateException("storage failed");
    when(repository.findImports("alice", 20, null)).thenThrow(failure);
    var service = new FindImportsService(repository, objectMapper);

    assertThatThrownBy(
            () ->
                service.findImports(
                    new RequestContext("alice"), new FindImportsRequest(null, null)))
        .isSameAs(failure);
  }

  @Test
  void getImportProjectsRowsTotalsPhotosAndNullableFilename() throws Exception {
    importTable.putItem(parent("alice", "detail", null, "review", 9, 1_700_000_100L));
    dynamoDbClient.updateItem(
        request ->
            request
                .tableName(TABLE_NAME)
                .key(
                    Map.of(
                        ImportItem.PK, AttributeValue.fromS("USER#alice"),
                        ImportItem.SK, AttributeValue.fromS("IMPORT#detail")))
                .updateExpression("SET #error = :error")
                .expressionAttributeNames(Map.of("#error", "error"))
                .expressionAttributeValues(
                    Map.of(":error", AttributeValue.fromS("legacy processing error"))));
    importRowTable.putItem(
        row("alice", "detail", 1, "Expensive", "keep", "20.00", 100, "A1-0", null));
    importRowTable.putItem(
        row("alice", "detail", 2, "Free", "keep", "0.00", 101, "A1-1", List.of("p1", "p2")));
    importRowTable.putItem(
        row("alice", "detail", 3, "Discarded", "discard", "50.00", 102, "A1-2", null));
    importRowTable.putItem(row("alice", "detail", 4, "Pending", null, null, null, null, null));
    importRowTable.putItem(
        row("alice", "detail", 5, "Below photo threshold", "keep", "19.99", 103, "A1-3", null));

    var response =
        getImportService.getImport(new RequestContext("alice"), new GetImportRequest("detail"));

    assertThat(response.filename()).isNull();
    assertThat(response.rowCount()).isEqualTo(9);
    assertThat(response.createdAt()).isEqualTo(1_700_000_100L);
    assertThat(response.totalSuggestedPrice()).isEqualTo("39.99");
    assertThat(response.unitCount()).isNull();
    assertThat(response.placementInstructions()).isNull();
    assertThat(response.rows()).hasSize(5);
    assertThat(response.rows().get(0).condition()).isEqualTo(Condition.NM);
    assertThat(response.rows().get(0).decision()).isEqualTo(ImportDecision.KEEP);
    assertThat(response.rows().get(0).needsPhotos()).isTrue();
    assertThat(response.rows().get(1).photos()).containsExactly("p1", "p2");
    assertThat(response.rows().get(1).needsPhotos()).isFalse();
    assertThat(response.rows().get(3).decision()).isNull();
    assertThat(response.rows().get(4).needsPhotos()).isFalse();

    var json = objectMapper.valueToTree(response);
    assertThat(json.has("appraisal_error")).isFalse();
    assertThat(json.get("filename").isNull()).isTrue();
    assertThat(json.get("rows").get(0).get("condition").asText()).isEqualTo("NM");
    assertThat(json.get("rows").get(0).get("decision").asText()).isEqualTo("keep");
  }

  @Test
  void getImportBuildsPlacementFromSavedSequencesAndLocations() throws Exception {
    importTable.putItem(parent("alice", "confirmed", null, "confirmed", 8, 1_700_000_100L));
    int[] sequences = {4200, 4201, 4204, 4299, 4300};
    String[] locations = {"A42-0", "A42-1", "A42-4", "A42-99", "A43-0"};
    for (int index = 0; index < sequences.length; index++) {
      var row =
          row(
              "alice",
              "confirmed",
              index + 1,
              "Card " + index,
              "keep",
              "1.00",
              sequences[index],
              locations[index],
              null);
      importRowTable.putItem(row);
    }

    var response =
        getImportService.getImport(new RequestContext("alice"), new GetImportRequest("confirmed"));

    assertThat(response.rowCount()).isEqualTo(8);
    assertThat(response.unitCount()).isEqualTo(5);
    assertThat(response.placementInstructions())
        .containsExactly(
            new PlacementInstruction("A42", "A42-0", "A42-1", "Card 0", "Card 1", 2),
            new PlacementInstruction("A42", "A42-4", "A42-4", "Card 2", "Card 2", 1),
            new PlacementInstruction("A42", "A42-99", "A42-99", "Card 3", "Card 3", 1),
            new PlacementInstruction("A43", "A43-0", "A43-0", "Card 4", "Card 4", 1));
  }

  @Test
  void getImportReturnsEmptyPlacementForConfirmedImportWithoutKeepRows() throws Exception {
    importTable.putItem(parent("alice", "empty", null, "confirmed", 0, 1_700_000_100L));

    var response =
        getImportService.getImport(new RequestContext("alice"), new GetImportRequest("empty"));

    assertThat(response.unitCount()).isZero();
    assertThat(response.placementInstructions()).isEmpty();
    assertThat(response.totalSuggestedPrice()).isEqualTo("0.00");
  }

  @Test
  void getImportRejectsMissingAndUnknownMetadataVisibly() throws Exception {
    var repository = mock(ImportReadRepository.class);
    var service = new GetImportService(repository);
    when(repository.getImport("alice", "missing")).thenReturn(null);
    assertThatThrownBy(
            () -> service.getImport(new RequestContext("alice"), new GetImportRequest("missing")))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");
    verify(repository, never()).findRows("alice", "missing");

    importTable.putItem(parent("alice", "unknown", null, "failed", 0, 1_700_000_100L));
    assertThatThrownBy(
            () ->
                getImportService.getImport(
                    new RequestContext("alice"), new GetImportRequest("unknown")))
        .isInstanceOf(IllegalArgumentException.class);

    importTable.putItem(parent("alice", "bad-row", null, "review", 1, 1_700_000_100L));
    var badCondition = row("alice", "bad-row", 1, "Card", null, null, null, null, null);
    badCondition.setCondition("UNKNOWN");
    importRowTable.putItem(badCondition);
    assertThatThrownBy(
            () ->
                getImportService.getImport(
                    new RequestContext("alice"), new GetImportRequest("bad-row")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void getImportIsScopedToTheRequestUser() throws Exception {
    importTable.putItem(parent("alice", "same-id", null, "review", 1, 1_700_000_100L));
    importRowTable.putItem(row("alice", "same-id", 1, "Card", null, null, null, null, null));

    assertThatThrownBy(
            () ->
                getImportService.getImport(
                    new RequestContext("bob"), new GetImportRequest("same-id")))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");
  }

  @Test
  void getImportValidatesIdentifierBeforePersistenceAccess() throws Exception {
    var repository = mock(ImportReadRepository.class);
    var service = new GetImportService(repository);

    assertThatThrownBy(
            () -> service.getImport(new RequestContext("alice"), new GetImportRequest("  ")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("import_id is required");
    verifyNoInteractions(repository);
  }

  @Test
  void getImportReadsAllOrderedRowsAcrossDynamoDbPages() throws Exception {
    importTable.putItem(parent("alice", "many", null, "review", 600, 1_700_000_100L));
    var longName = "n".repeat(2_000);
    for (int position = 1; position <= 600; position++) {
      var row = row("alice", "many", position, longName + position, null, null, null, null, null);
      importRowTable.putItem(row);
    }

    var response =
        getImportService.getImport(new RequestContext("alice"), new GetImportRequest("many"));

    assertThat(response.rows()).hasSize(600);
    assertThat(response.rows())
        .extracting(ImportRow::position)
        .containsExactlyElementsOf(IntStream.rangeClosed(1, 600).boxed().toList());
  }

  private static ImportItem item(
      String user, String importId, String filename, String status, int rowCount, long createdAt) {
    var item = new ImportItem();
    item.setPk(ImportItem.formatPk(user));
    item.setSk(ImportItem.formatSk(importId));
    item.setImportId(importId);
    item.setGame("mtg");
    item.setFilename(filename);
    item.setStatus(status);
    item.setRowCount(rowCount);
    item.setCreatedAt(Instant.ofEpochSecond(createdAt));
    return item;
  }

  private static ImportItem parent(
      String user, String importId, String filename, String status, int rowCount, long createdAt) {
    var item = item(user, importId, filename, status, rowCount, createdAt);
    return item;
  }

  private static ImportRowItem row(
      String user,
      String importId,
      int position,
      String name,
      String decision,
      String suggestedPrice,
      Integer sequenceNumber,
      String location,
      List<String> photoIds) {
    var row = new ImportRowItem();
    row.setPk(ImportRowItem.formatPk(user, importId));
    row.setSk(ImportRowItem.formatSk(position));
    row.setPosition(position);
    row.setName(name);
    row.setSetCode("SET");
    row.setSetName("Set Name");
    row.setCollectorNumber(Integer.toString(position));
    row.setFinish("normal");
    row.setCondition("NM");
    row.setExternalId("opaque-" + position);
    row.setDecision(decision);
    row.setMarketPrice(suggestedPrice);
    row.setSuggestedPrice(suggestedPrice);
    row.setSequenceNumber(sequenceNumber);
    row.setSkuId(sequenceNumber == null ? null : "mtg#source#opaque#normal#NM");
    row.setLocation(location);
    if (photoIds != null) {
      row.setPhotos(
          photoIds.stream()
              .map(
                  photoId -> {
                    var photo = new ImportRowItem.Photo();
                    photo.setPhotoId(photoId);
                    return photo;
                  })
              .toList());
    }
    return row;
  }
}
