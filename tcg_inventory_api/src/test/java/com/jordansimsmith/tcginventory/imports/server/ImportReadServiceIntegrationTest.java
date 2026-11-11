package com.jordansimsmith.tcginventory.imports.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.imports.client.FindImportsRequest;
import com.jordansimsmith.tcginventory.imports.client.ImportStatus;
import com.jordansimsmith.tcginventory.imports.client.ImportSummary;
import java.time.Instant;
import java.util.Map;
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

  private final ObjectMapper objectMapper = new ObjectMapper();
  private FindImportsService findImportsService;

  @BeforeAll
  static void setUpBeforeClass() {
    dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    importTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(ImportItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, importTable);
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    findImportsService =
        new FindImportsService(new ImportReadRepository(importTable), objectMapper);
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
}
