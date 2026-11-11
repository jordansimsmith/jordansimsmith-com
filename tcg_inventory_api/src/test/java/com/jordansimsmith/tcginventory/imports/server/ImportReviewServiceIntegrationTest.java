package com.jordansimsmith.tcginventory.imports.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRowPhotoRequest;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRequest;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRowRequest;
import com.jordansimsmith.tcginventory.imports.client.GetImportRequest;
import com.jordansimsmith.tcginventory.imports.client.ImportRow;
import com.jordansimsmith.tcginventory.imports.client.UpdateImportRowRequest;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.photos.client.FakePhotosService;
import com.jordansimsmith.tcginventory.photos.client.GetPhotoBytesRequest;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

@Testcontainers
class ImportReviewServiceIntegrationTest {
  private static final String TABLE_NAME = "imports_review_test";
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<ImportItem> importTable;
  private static DynamoDbTable<ImportRowItem> importRowTable;

  private ImportReadRepository importReadRepository;
  private ImportMutationRepository importMutationRepository;
  private UpdateImportRowService updateImportRowService;
  private DeleteImportRowService deleteImportRowService;
  private DeleteImportService deleteImportService;
  private FakePhotosService fakePhotosService;
  private CreateImportRowPhotoService createImportRowPhotoService;

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
    importReadRepository = new ImportReadRepository(importTable, importRowTable);
    importMutationRepository = new ImportMutationRepository(dynamoDbClient, TABLE_NAME);
    updateImportRowService =
        new UpdateImportRowService(importReadRepository, importMutationRepository);
    deleteImportRowService =
        new DeleteImportRowService(importReadRepository, importMutationRepository);
    deleteImportService = new DeleteImportService(importReadRepository, importMutationRepository);
    fakePhotosService = new FakePhotosService();
    createImportRowPhotoService =
        new CreateImportRowPhotoService(
            importReadRepository, importMutationRepository, fakePhotosService);
  }

  @Test
  void updateImportRowChangesOnlyCondition() throws Exception {
    importTable.putItem(importItem("alice", "import-1", "review"));
    var row = importRow("alice", "import-1", 1);
    importRowTable.putItem(row);
    dynamoDbClient.updateItem(
        request ->
            request
                .tableName(TABLE_NAME)
                .key(
                    Map.of(
                        ImportRowItem.PK, AttributeValue.fromS(row.getPk()),
                        ImportRowItem.SK, AttributeValue.fromS(row.getSk())))
                .updateExpression("SET unrelated = :unrelated")
                .expressionAttributeValues(Map.of(":unrelated", AttributeValue.fromS("keep-me"))));

    var response =
        updateImportRowService.updateImportRow(
            new RequestContext("alice"), new UpdateImportRowRequest("import-1", 1, Condition.LP));

    assertThat(response).isNotNull();
    var saved = importReadRepository.getRow("alice", "import-1", 1);
    assertThat(saved.getCondition()).isEqualTo("LP");
    assertThat(saved.getDecision()).isEqualTo("keep");
    assertThat(saved.getDecisionReason()).isEqualTo("matched");
    assertThat(saved.getMarketPrice()).isEqualTo("1.50");
    assertThat(saved.getSuggestedPrice()).isEqualTo("25.00");
    assertThat(saved.getPhotos())
        .extracting(ImportRowItem.Photo::getPhotoId)
        .containsExactly("photo-1");
    assertThat(saved.getSkuId()).isEqualTo("sku-1");
    assertThat(saved.getSequenceNumber()).isEqualTo(42);
    assertThat(saved.getLocation()).isEqualTo("A0-42");
    var stored =
        dynamoDbClient
            .getItem(
                request ->
                    request
                        .tableName(TABLE_NAME)
                        .key(
                            Map.of(
                                ImportRowItem.PK, AttributeValue.fromS(row.getPk()),
                                ImportRowItem.SK, AttributeValue.fromS(row.getSk()))))
            .item();
    assertThat(stored.get("unrelated")).isEqualTo(AttributeValue.fromS("keep-me"));
  }

  @Test
  void invalidRequestsFailBeforeStorageAccess() {
    var readRepository = mock(ImportReadRepository.class);
    var mutationRepository = mock(ImportMutationRepository.class);
    var service = new UpdateImportRowService(readRepository, mutationRepository);

    assertThatThrownBy(
            () ->
                service.updateImportRow(
                    new RequestContext("alice"), new UpdateImportRowRequest("  ", 1, Condition.NM)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("import_id is required");
    assertThatThrownBy(
            () ->
                service.updateImportRow(
                    new RequestContext("alice"),
                    new UpdateImportRowRequest("import-1", 0, Condition.NM)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("position must be positive");
    assertThatThrownBy(
            () ->
                service.updateImportRow(
                    new RequestContext("alice"), new UpdateImportRowRequest("import-1", 1, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("invalid condition");
    verifyNoInteractions(readRepository, mutationRepository);
  }

  @Test
  void missingImportsRowsAndNonReviewImportsFail() {
    assertThatThrownBy(
            () ->
                updateImportRowService.updateImportRow(
                    new RequestContext("alice"),
                    new UpdateImportRowRequest("missing", 1, Condition.NM)))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");

    importTable.putItem(importItem("alice", "import-1", "appraising"));
    assertThatThrownBy(
            () ->
                updateImportRowService.updateImportRow(
                    new RequestContext("alice"),
                    new UpdateImportRowRequest("import-1", 1, Condition.NM)))
        .isInstanceOf(ConflictException.class)
        .hasMessage("import is not in review status");

    importTable.putItem(importItem("alice", "import-1", "review"));
    assertThatThrownBy(
            () ->
                updateImportRowService.updateImportRow(
                    new RequestContext("bob"),
                    new UpdateImportRowRequest("import-1", 1, Condition.NM)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                updateImportRowService.updateImportRow(
                    new RequestContext("alice"),
                    new UpdateImportRowRequest("import-1", 1, Condition.NM)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void transactionRejectsChangedParentOrMissingRow() {
    importTable.putItem(importItem("alice", "import-1", "appraising"));
    var row = importRow("alice", "import-1", 1);
    importRowTable.putItem(row);

    assertThatThrownBy(
            () -> importMutationRepository.updateRowCondition("alice", "import-1", 1, "LP"))
        .isInstanceOf(TransactionCanceledException.class);
    assertThat(importReadRepository.getRow("alice", "import-1", 1).getCondition()).isEqualTo("NM");

    importTable.putItem(importItem("alice", "import-1", "review"));
    importRowTable.deleteItem(row);
    assertThatThrownBy(
            () -> importMutationRepository.updateRowCondition("alice", "import-1", 1, "LP"))
        .isInstanceOf(TransactionCanceledException.class);
  }

  @Test
  void storageFailurePropagatesWithoutMutation() throws Exception {
    var failure = new IllegalStateException("storage unavailable");
    var readRepository = mock(ImportReadRepository.class);
    var mutationRepository = mock(ImportMutationRepository.class);
    when(readRepository.getImport("alice", "import-1")).thenThrow(failure);
    var service = new UpdateImportRowService(readRepository, mutationRepository);

    assertThatThrownBy(
            () ->
                service.updateImportRow(
                    new RequestContext("alice"),
                    new UpdateImportRowRequest("import-1", 1, Condition.NM)))
        .isSameAs(failure);
    verifyNoInteractions(mutationRepository);
  }

  @Test
  void deleteImportRowPreservesOriginalCountRowsAndPhotoReferences() throws Exception {
    var parent = importItem("alice", "import-1", "review");
    parent.setRowCount(2);
    importTable.putItem(parent);
    var first = importRow("alice", "import-1", 1);
    var second = importRow("alice", "import-1", 2);
    second.setSuggestedPrice("2.50");
    importRowTable.putItem(first);
    importRowTable.putItem(second);

    deleteImportRowService.deleteImportRow(
        new RequestContext("alice"), new DeleteImportRowRequest("import-1", 1));

    var savedParent = importReadRepository.getImport("alice", "import-1");
    assertThat(savedParent.getRowCount()).isEqualTo(2);
    assertThat(savedParent.getCreatedAt()).isEqualTo(parent.getCreatedAt());
    assertThat(importReadRepository.getRow("alice", "import-1", 1)).isNull();
    var detail =
        new GetImportService(importReadRepository)
            .getImport(new RequestContext("alice"), new GetImportRequest("import-1"));
    assertThat(detail.rowCount()).isEqualTo(2);
    assertThat(detail.totalSuggestedPrice()).isEqualTo("2.50");
    assertThat(detail.rows()).extracting(ImportRow::position).containsExactly(2);
    assertThat(detail.rows().getFirst().photos()).containsExactly("photo-1");
  }

  @Test
  void deletingLastImportRowKeepsOriginalCountAndReturnsZeroTotal() throws Exception {
    var parent = importItem("alice", "import-1", "review");
    importTable.putItem(parent);
    importRowTable.putItem(importRow("alice", "import-1", 1));

    deleteImportRowService.deleteImportRow(
        new RequestContext("alice"), new DeleteImportRowRequest("import-1", 1));

    var detail =
        new GetImportService(importReadRepository)
            .getImport(new RequestContext("alice"), new GetImportRequest("import-1"));
    assertThat(detail.rowCount()).isEqualTo(1);
    assertThat(detail.rows()).isEmpty();
    assertThat(detail.totalSuggestedPrice()).isEqualTo("0.00");
  }

  @Test
  void deleteImportRowValidatesAndScopesReads() {
    var readRepository = mock(ImportReadRepository.class);
    var mutationRepository = mock(ImportMutationRepository.class);
    var service = new DeleteImportRowService(readRepository, mutationRepository);
    assertThatThrownBy(
            () ->
                service.deleteImportRow(
                    new RequestContext("alice"), new DeleteImportRowRequest(" ", 1)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("import_id is required");
    assertThatThrownBy(
            () ->
                service.deleteImportRow(
                    new RequestContext("alice"), new DeleteImportRowRequest("import-1", 0)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("position must be positive");
    verifyNoInteractions(readRepository, mutationRepository);

    assertThatThrownBy(
            () ->
                service.deleteImportRow(
                    new RequestContext("bob"), new DeleteImportRowRequest("opaque/id", 1)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void deleteImportRowRequiresReviewAndExistingParentAndRow() {
    assertThatThrownBy(
            () ->
                deleteImportRowService.deleteImportRow(
                    new RequestContext("alice"), new DeleteImportRowRequest("missing", 1)))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");
    importTable.putItem(importItem("alice", "import-1", "appraising"));
    assertThatThrownBy(
            () ->
                deleteImportRowService.deleteImportRow(
                    new RequestContext("alice"), new DeleteImportRowRequest("import-1", 1)))
        .isInstanceOf(ConflictException.class)
        .hasMessage("import is not in review status");
    importTable.putItem(importItem("alice", "import-1", "review"));
    assertThatThrownBy(
            () ->
                deleteImportRowService.deleteImportRow(
                    new RequestContext("alice"), new DeleteImportRowRequest("import-1", 1)))
        .isInstanceOf(NotFoundException.class);
    var row = importRow("alice", "import-1", 1);
    importRowTable.putItem(row);
    assertThatThrownBy(
            () ->
                deleteImportRowService.deleteImportRow(
                    new RequestContext("bob"), new DeleteImportRowRequest("import-1", 1)))
        .isInstanceOf(NotFoundException.class);
    assertThat(importReadRepository.getRow("alice", "import-1", 1)).isNotNull();
  }

  @Test
  void deleteImportRowTransactionRejectsChangedParentAndMissingRow() {
    importTable.putItem(importItem("alice", "import-1", "review"));
    var row = importRow("alice", "import-1", 1);
    importRowTable.putItem(row);
    assertThat(importReadRepository.getImport("alice", "import-1").getStatus()).isEqualTo("review");
    assertThat(importReadRepository.getRow("alice", "import-1", 1)).isNotNull();
    importTable.putItem(importItem("alice", "import-1", "appraising"));
    assertThatThrownBy(() -> importMutationRepository.deleteRow("alice", "import-1", 1))
        .isInstanceOf(TransactionCanceledException.class);
    assertThat(importReadRepository.getRow("alice", "import-1", 1)).isNotNull();

    importTable.putItem(importItem("alice", "import-1", "review"));
    importRowTable.deleteItem(row);
    assertThatThrownBy(() -> importMutationRepository.deleteRow("alice", "import-1", 1))
        .isInstanceOf(TransactionCanceledException.class);
  }

  @Test
  void deleteImportRowPropagatesReadFailureWithoutMutation() throws Exception {
    var failure = new IllegalStateException("storage unavailable");
    var readRepository = mock(ImportReadRepository.class);
    var mutationRepository = mock(ImportMutationRepository.class);
    when(readRepository.getImport("alice", "import-1")).thenThrow(failure);
    var service = new DeleteImportRowService(readRepository, mutationRepository);

    assertThatThrownBy(
            () ->
                service.deleteImportRow(
                    new RequestContext("alice"), new DeleteImportRowRequest("import-1", 1)))
        .isSameAs(failure);
    verifyNoInteractions(mutationRepository);
  }

  @Test
  void deleteImportRowRequestAndEmptyResponseUseJsonContract() throws Exception {
    var objectMapper = new ObjectMapper();
    importTable.putItem(importItem("alice", "import-1", "review"));
    importRowTable.putItem(importRow("alice", "import-1", 4));

    assertThat(objectMapper.writeValueAsString(new DeleteImportRowRequest("import-1", 4)))
        .isEqualTo("{\"import_id\":\"import-1\",\"position\":4}");
    assertThat(
            objectMapper.readValue(
                "{\"import_id\":\"import-1\",\"position\":4}", DeleteImportRowRequest.class))
        .isEqualTo(new DeleteImportRowRequest("import-1", 4));
    assertThat(
            objectMapper.writeValueAsString(
                deleteImportRowService.deleteImportRow(
                    new RequestContext("alice"), new DeleteImportRowRequest("import-1", 4))))
        .isEqualTo("{}");
  }

  @Test
  void deleteImportRemovesParentAndEveryRowButKeepsOtherRecordsAndPhotoObjects() throws Exception {
    var parent = importItem("alice", "import-1", "review");
    importTable.putItem(parent);
    importRowTable.putItem(importRow("alice", "import-1", 1));
    importRowTable.putItem(importRow("alice", "import-1", 2));
    importTable.putItem(importItem("bob", "import-1", "review"));
    dynamoDbClient.putItem(
        request ->
            request
                .tableName(TABLE_NAME)
                .item(
                    Map.of(
                        "pk", AttributeValue.fromS("USER#alice#IMPORT_OPERATIONS"),
                        "sk", AttributeValue.fromS("CREATE#import-operation"),
                        "import_id", AttributeValue.fromS("import-1"))));

    assertThat(
            deleteImportService.deleteImport(
                new RequestContext("alice"), new DeleteImportRequest("import-1")))
        .isNotNull();

    assertThat(importReadRepository.getImport("alice", "import-1")).isNull();
    assertThat(importReadRepository.findRows("alice", "import-1")).isEmpty();
    assertThat(importReadRepository.getImport("bob", "import-1")).isNotNull();
    assertThat(
            dynamoDbClient.getItem(
                request ->
                    request
                        .tableName(TABLE_NAME)
                        .key(
                            Map.of(
                                "pk", AttributeValue.fromS("USER#alice#IMPORT_OPERATIONS"),
                                "sk", AttributeValue.fromS("CREATE#import-operation")))))
        .isNotNull();
  }

  @Test
  void deleteImportCleansUpRowsAcrossQueryPages() throws Exception {
    importTable.putItem(importItem("alice", "import-1", "review"));
    putLargeRows("alice", "import-1", 1100);

    deleteImportService.deleteImport(
        new RequestContext("alice"), new DeleteImportRequest("import-1"));

    assertThat(importReadRepository.findRows("alice", "import-1")).isEmpty();
    assertThat(importReadRepository.getImport("alice", "import-1")).isNull();
  }

  @Test
  void deleteImportAllowsAnEmptyImportAndReturnsEmptyJson() throws Exception {
    importTable.putItem(importItem("alice", "empty", "review"));
    var objectMapper = new ObjectMapper();

    assertThat(objectMapper.writeValueAsString(new DeleteImportRequest("empty")))
        .isEqualTo("{\"import_id\":\"empty\"}");
    assertThat(
            objectMapper.writeValueAsString(
                deleteImportService.deleteImport(
                    new RequestContext("alice"), new DeleteImportRequest("empty"))))
        .isEqualTo("{}");
    assertThat(importReadRepository.getImport("alice", "empty")).isNull();
  }

  @Test
  void deleteImportValidatesBeforeReadsAndUsesAuthenticatedUserAndExactId() throws Exception {
    var readRepository = mock(ImportReadRepository.class);
    var mutationRepository = mock(ImportMutationRepository.class);
    var service = new DeleteImportService(readRepository, mutationRepository);
    assertThatThrownBy(
            () -> service.deleteImport(new RequestContext("alice"), new DeleteImportRequest(" ")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("import_id is required");
    verifyNoInteractions(readRepository, mutationRepository);

    when(readRepository.getImport("alice", "opaque/id"))
        .thenReturn(importItem("alice", "opaque/id", "review"));
    when(readRepository.findRows("alice", "opaque/id")).thenReturn(List.of());
    service.deleteImport(new RequestContext("alice"), new DeleteImportRequest("opaque/id"));
    verify(readRepository).getImport("alice", "opaque/id");
    verify(mutationRepository).deleteImport("alice", "opaque/id");
    verify(mutationRepository).deleteRows(List.of());
  }

  @Test
  void deleteImportRequiresExistingReviewImport() {
    var readRepository = mock(ImportReadRepository.class);
    var mutationRepository = mock(ImportMutationRepository.class);
    var service = new DeleteImportService(readRepository, mutationRepository);
    when(readRepository.getImport("alice", "missing")).thenReturn(null);
    assertThatThrownBy(
            () ->
                service.deleteImport(
                    new RequestContext("alice"), new DeleteImportRequest("missing")))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");
    when(readRepository.getImport("alice", "active"))
        .thenReturn(importItem("alice", "active", "appraising"));
    assertThatThrownBy(
            () ->
                service.deleteImport(
                    new RequestContext("alice"), new DeleteImportRequest("active")))
        .isInstanceOf(ConflictException.class)
        .hasMessage("import is not in a deletable status");
    verifyNoInteractions(mutationRepository);
  }

  @Test
  void deleteImportConditionalDeleteRejectsParentTransitionBeforeRowCleanup() {
    importTable.putItem(importItem("alice", "import-1", "review"));
    importRowTable.putItem(importRow("alice", "import-1", 1));
    var mutationRepository =
        new ImportMutationRepository(dynamoDbClient, TABLE_NAME) {
          @Override
          public void deleteImport(String user, String importId) {
            var parent =
                importTable.getItem(
                    Key.builder()
                        .partitionValue("USER#alice")
                        .sortValue("IMPORT#import-1")
                        .build());
            parent.setStatus("appraising");
            importTable.putItem(parent);
            super.deleteImport(user, importId);
          }
        };
    var service = new DeleteImportService(importReadRepository, mutationRepository);

    assertThatThrownBy(
            () ->
                service.deleteImport(
                    new RequestContext("alice"), new DeleteImportRequest("import-1")))
        .isInstanceOf(ConditionalCheckFailedException.class);
    assertThat(importReadRepository.getImport("alice", "import-1").getStatus())
        .isEqualTo("appraising");
    assertThat(importReadRepository.getRow("alice", "import-1", 1)).isNotNull();
  }

  @Test
  void deleteImportQueryFailureLeavesRowsAfterParentDeletion() {
    importTable.putItem(importItem("alice", "import-1", "review"));
    importRowTable.putItem(importRow("alice", "import-1", 1));
    var failure = new IllegalStateException("row query failed");
    var readRepository = mock(ImportReadRepository.class);
    when(readRepository.getImport("alice", "import-1"))
        .thenReturn(importItem("alice", "import-1", "review"));
    when(readRepository.findRows("alice", "import-1")).thenThrow(failure);
    var service = new DeleteImportService(readRepository, importMutationRepository);

    assertThatThrownBy(
            () ->
                service.deleteImport(
                    new RequestContext("alice"), new DeleteImportRequest("import-1")))
        .isSameAs(failure);
    assertThat(importReadRepository.getImport("alice", "import-1")).isNull();
    assertThat(importReadRepository.getRow("alice", "import-1", 1)).isNotNull();
  }

  @Test
  void deleteImportCleanupFailureLeavesCompletedDeletesAndOrphanedRows() {
    importTable.putItem(importItem("alice", "import-1", "review"));
    importRowTable.putItem(importRow("alice", "import-1", 1));
    importRowTable.putItem(importRow("alice", "import-1", 2));
    var failure = new IllegalStateException("row cleanup failed");
    var mutationRepository =
        new ImportMutationRepository(dynamoDbClient, TABLE_NAME) {
          @Override
          public void deleteRows(List<ImportRowItem> rows) {
            super.deleteRows(rows.subList(0, 1));
            throw failure;
          }
        };
    var service = new DeleteImportService(importReadRepository, mutationRepository);

    assertThatThrownBy(
            () ->
                service.deleteImport(
                    new RequestContext("alice"), new DeleteImportRequest("import-1")))
        .isSameAs(failure);
    assertThat(importReadRepository.getImport("alice", "import-1")).isNull();
    assertThat(importReadRepository.findRows("alice", "import-1")).hasSize(1);
    assertThatThrownBy(
            () ->
                new GetImportService(importReadRepository)
                    .getImport(new RequestContext("alice"), new GetImportRequest("import-1")))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void deleteImportFailureStopsBeforeReadingRows() {
    var readRepository = mock(ImportReadRepository.class);
    var mutationRepository = mock(ImportMutationRepository.class);
    var failure = new IllegalStateException("delete failed");
    when(readRepository.getImport("alice", "import-1"))
        .thenReturn(importItem("alice", "import-1", "review"));
    doThrow(failure).when(mutationRepository).deleteImport("alice", "import-1");
    var service = new DeleteImportService(readRepository, mutationRepository);

    assertThatThrownBy(
            () ->
                service.deleteImport(
                    new RequestContext("alice"), new DeleteImportRequest("import-1")))
        .isSameAs(failure);
    verify(readRepository, never()).findRows("alice", "import-1");
    verify(mutationRepository, never()).deleteRows(anyList());
  }

  @Test
  void createImportRowPhotoAppendsInOrderAndPreservesRowAttributes() throws Exception {
    importTable.putItem(importItem("alice", "import-1", "review"));
    var row = importRow("alice", "import-1", 1);
    row.setSuggestedPrice("25.00");
    row.setPhotos(List.of(ImportRowItem.Photo.create("first-photo")));
    importRowTable.putItem(row);
    dynamoDbClient.updateItem(
        request ->
            request
                .tableName(TABLE_NAME)
                .key(
                    Map.of(
                        ImportRowItem.PK, AttributeValue.fromS(row.getPk()),
                        ImportRowItem.SK, AttributeValue.fromS(row.getSk())))
                .updateExpression("SET unrelated = :unrelated")
                .expressionAttributeValues(
                    Map.of(":unrelated", AttributeValue.fromS("preserved"))));

    createImportRowPhotoService.createImportRowPhoto(
        new RequestContext("alice"),
        new CreateImportRowPhotoRequest("import-1", 1, new byte[] {1, 2, 3}));

    var saved = importReadRepository.getRow("alice", "import-1", 1);
    assertThat(saved.getPhotos())
        .extracting(ImportRowItem.Photo::getPhotoId)
        .containsExactly("first-photo", "FAKE_PHOTO_0000000001");
    assertThat(saved.getDecision()).isEqualTo("keep");
    assertThat(saved.getSuggestedPrice()).isEqualTo("1.25");
    assertThat(saved.getSkuId()).isEqualTo("sku-1");
    assertThat(
            dynamoDbClient
                .getItem(
                    request ->
                        request
                            .tableName(TABLE_NAME)
                            .key(
                                Map.of(
                                    ImportRowItem.PK, AttributeValue.fromS(row.getPk()),
                                    ImportRowItem.SK, AttributeValue.fromS(row.getSk()))))
                .item()
                .get("unrelated"))
        .isEqualTo(AttributeValue.fromS("preserved"));
    assertThat(
            fakePhotosService
                .getPhotoBytes(
                    new RequestContext("alice"), new GetPhotoBytesRequest("FAKE_PHOTO_0000000001"))
                .photoBytes())
        .containsExactly(1, 2, 3);
    var detail =
        new GetImportService(importReadRepository)
            .getImport(new RequestContext("alice"), new GetImportRequest("import-1"));
    assertThat(detail.rows().getFirst().photos())
        .containsExactly("first-photo", "FAKE_PHOTO_0000000001");
    assertThat(detail.rows().getFirst().needsPhotos()).isFalse();
  }

  @Test
  void createImportRowPhotoSupportsMissingPhotoListAndRequestJson() throws Exception {
    importTable.putItem(importItem("alice", "opaque/id", "review"));
    var row = importRow("alice", "opaque/id", 1);
    row.setPhotos(null);
    importRowTable.putItem(row);
    var objectMapper = new ObjectMapper();

    var response =
        createImportRowPhotoService.createImportRowPhoto(
            new RequestContext("alice"),
            new CreateImportRowPhotoRequest("opaque/id", 1, new byte[] {4}));

    assertThat(importReadRepository.getRow("alice", "opaque/id", 1).getPhotos())
        .extracting(ImportRowItem.Photo::getPhotoId)
        .containsExactly("FAKE_PHOTO_0000000001");
    assertThat(
            objectMapper.writeValueAsString(
                new CreateImportRowPhotoRequest("opaque/id", 1, new byte[] {4})))
        .isEqualTo("{\"import_id\":\"opaque/id\",\"position\":1,\"photo_bytes\":\"BA==\"}");
    assertThat(objectMapper.writeValueAsString(response)).isEqualTo("{}");
  }

  @Test
  void createImportRowPhotoValidatesPolicyBeforePhotosCall() {
    var readRepository = mock(ImportReadRepository.class);
    var mutationRepository = mock(ImportMutationRepository.class);
    var photosService = mock(PhotosService.class);
    var service =
        new CreateImportRowPhotoService(readRepository, mutationRepository, photosService);
    assertThatThrownBy(
            () ->
                service.createImportRowPhoto(
                    new RequestContext("alice"),
                    new CreateImportRowPhotoRequest(" ", 1, new byte[] {1})))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("import_id is required");
    assertThatThrownBy(
            () ->
                service.createImportRowPhoto(
                    new RequestContext("alice"),
                    new CreateImportRowPhotoRequest("import-1", 0, new byte[] {1})))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("position must be positive");
    verifyNoInteractions(readRepository, mutationRepository, photosService);

    when(readRepository.getImport("alice", "import-1"))
        .thenReturn(importItem("alice", "import-1", "review"));
    var nonKeepRow = importRow("alice", "import-1", 1);
    nonKeepRow.setDecision("discard");
    when(readRepository.getRow("alice", "import-1", 1)).thenReturn(nonKeepRow);
    assertThatThrownBy(
            () ->
                service.createImportRowPhoto(
                    new RequestContext("alice"),
                    new CreateImportRowPhotoRequest("import-1", 1, new byte[] {1})))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("photos can only be created on keep rows");
    verifyNoInteractions(photosService, mutationRepository);
  }

  @Test
  void createImportRowPhotoEnforcesReviewKeepAndPhotoLimit() {
    assertThatThrownBy(
            () ->
                createImportRowPhotoService.createImportRowPhoto(
                    new RequestContext("alice"),
                    new CreateImportRowPhotoRequest("missing", 1, new byte[] {1})))
        .isInstanceOf(NotFoundException.class);
    importTable.putItem(importItem("alice", "import-1", "review"));
    assertThatThrownBy(
            () ->
                createImportRowPhotoService.createImportRowPhoto(
                    new RequestContext("alice"),
                    new CreateImportRowPhotoRequest("import-1", 1, new byte[] {1})))
        .isInstanceOf(NotFoundException.class);
    importRowTable.putItem(importRow("alice", "import-1", 1));
    assertThatThrownBy(
            () ->
                createImportRowPhotoService.createImportRowPhoto(
                    new RequestContext("bob"),
                    new CreateImportRowPhotoRequest("import-1", 1, new byte[] {1})))
        .isInstanceOf(NotFoundException.class);
    importTable.putItem(importItem("alice", "import-1", "appraising"));
    assertThatThrownBy(
            () ->
                createImportRowPhotoService.createImportRowPhoto(
                    new RequestContext("alice"),
                    new CreateImportRowPhotoRequest("import-1", 1, new byte[] {1})))
        .isInstanceOf(ConflictException.class)
        .hasMessage("import is not in review status");
    importTable.putItem(importItem("alice", "import-1", "review"));
    var row = importRow("alice", "import-1", 1);
    row.setPhotos(
        List.of(
            ImportRowItem.Photo.create("1"),
            ImportRowItem.Photo.create("2"),
            ImportRowItem.Photo.create("3"),
            ImportRowItem.Photo.create("4"),
            ImportRowItem.Photo.create("5")));
    importRowTable.putItem(row);
    assertThatThrownBy(
            () ->
                createImportRowPhotoService.createImportRowPhoto(
                    new RequestContext("alice"),
                    new CreateImportRowPhotoRequest("import-1", 1, new byte[] {1})))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("a row may have at most 5 photos");
  }

  @Test
  void createImportRowPhotoLeavesPhotosObjectWhenReferenceWriteFails() {
    importTable.putItem(importItem("alice", "import-1", "review"));
    importRowTable.putItem(importRow("alice", "import-1", 1));
    var failure = new IllegalStateException("transaction failed");
    var mutationRepository = mock(ImportMutationRepository.class);
    doThrow(failure).when(mutationRepository).updateRowPhotos("alice", "import-1", 1, anyList());
    var service =
        new CreateImportRowPhotoService(
            importReadRepository, mutationRepository, fakePhotosService);

    assertThatThrownBy(
            () ->
                service.createImportRowPhoto(
                    new RequestContext("alice"),
                    new CreateImportRowPhotoRequest("import-1", 1, new byte[] {9})))
        .isSameAs(failure);
    assertThat(
            fakePhotosService
                .getPhotoBytes(
                    new RequestContext("alice"), new GetPhotoBytesRequest("FAKE_PHOTO_0000000001"))
                .photoBytes())
        .containsExactly(9);
    assertThat(importReadRepository.getRow("alice", "import-1", 1).getPhotos())
        .extracting(ImportRowItem.Photo::getPhotoId)
        .containsExactly("photo-1");
  }

  @Test
  void createImportRowPhotoDoesNotWriteReferenceWhenPhotosFails() throws Exception {
    importTable.putItem(importItem("alice", "import-1", "review"));
    importRowTable.putItem(importRow("alice", "import-1", 1));
    var photosService = mock(PhotosService.class);
    var failure = new NotFoundException("photo service failed");
    when(photosService.createPhoto(any(), any())).thenThrow(failure);
    var service =
        new CreateImportRowPhotoService(
            importReadRepository, importMutationRepository, photosService);

    assertThatThrownBy(
            () ->
                service.createImportRowPhoto(
                    new RequestContext("alice"),
                    new CreateImportRowPhotoRequest("import-1", 1, new byte[] {1})))
        .isSameAs(failure);
    assertThat(importReadRepository.getRow("alice", "import-1", 1).getPhotos())
        .extracting(ImportRowItem.Photo::getPhotoId)
        .containsExactly("photo-1");
  }

  @Test
  void updateRowPhotosTransactionRejectsChangedParentAndMissingRow() {
    importTable.putItem(importItem("alice", "import-1", "review"));
    var row = importRow("alice", "import-1", 1);
    importRowTable.putItem(row);
    var photos = List.of(ImportRowItem.Photo.create("new-photo"));
    importTable.putItem(importItem("alice", "import-1", "appraising"));
    assertThatThrownBy(
            () -> importMutationRepository.updateRowPhotos("alice", "import-1", 1, photos))
        .isInstanceOf(TransactionCanceledException.class);
    assertThat(importReadRepository.getRow("alice", "import-1", 1).getPhotos())
        .extracting(ImportRowItem.Photo::getPhotoId)
        .containsExactly("photo-1");
    importTable.putItem(importItem("alice", "import-1", "review"));
    importRowTable.deleteItem(row);
    assertThatThrownBy(
            () -> importMutationRepository.updateRowPhotos("alice", "import-1", 1, photos))
        .isInstanceOf(TransactionCanceledException.class);
  }

  private void putLargeRows(String user, String importId, int count) {
    for (int first = 1; first <= count; first += 25) {
      var requests = new ArrayList<WriteRequest>();
      for (int position = first; position <= Math.min(first + 24, count); position++) {
        var item =
            Map.of(
                ImportRowItem.PK,
                AttributeValue.fromS(ImportRowItem.formatPk(user, importId)),
                ImportRowItem.SK,
                AttributeValue.fromS(ImportRowItem.formatSk(position)),
                ImportRowItem.POSITION,
                AttributeValue.fromN(Integer.toString(position)),
                "padding",
                AttributeValue.fromS("x".repeat(1000)));
        requests.add(
            WriteRequest.builder().putRequest(PutRequest.builder().item(item).build()).build());
      }
      dynamoDbClient.batchWriteItem(
          BatchWriteItemRequest.builder().requestItems(Map.of(TABLE_NAME, requests)).build());
    }
  }

  private static ImportItem importItem(String user, String importId, String status) {
    var item = new ImportItem();
    item.setPk(ImportItem.formatPk(user));
    item.setSk(ImportItem.formatSk(importId));
    item.setImportId(importId);
    item.setGame("mtg");
    item.setStatus(status);
    item.setRowCount(1);
    item.setCreatedAt(Instant.ofEpochSecond(1_700_000_000L));
    return item;
  }

  private static ImportRowItem importRow(String user, String importId, int position) {
    var row = new ImportRowItem();
    row.setPk(ImportRowItem.formatPk(user, importId));
    row.setSk(ImportRowItem.formatSk(position));
    row.setPosition(position);
    row.setName("Card");
    row.setSetCode("SET");
    row.setSetName("Set");
    row.setCollectorNumber("1");
    row.setFinish("normal");
    row.setCondition("NM");
    row.setExternalId("external");
    row.setDecision("keep");
    row.setDecisionReason("matched");
    row.setMarketPrice("1.50");
    row.setSuggestedPrice("1.25");
    var photo = new ImportRowItem.Photo();
    photo.setPhotoId("photo-1");
    row.setPhotos(List.of(photo));
    row.setSkuId("sku-1");
    row.setSequenceNumber(42);
    row.setLocation("A0-42");
    return row;
  }
}
