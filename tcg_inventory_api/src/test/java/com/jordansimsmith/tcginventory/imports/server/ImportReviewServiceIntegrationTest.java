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
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.imports.client.DeleteImportRowRequest;
import com.jordansimsmith.tcginventory.imports.client.GetImportRequest;
import com.jordansimsmith.tcginventory.imports.client.ImportRow;
import com.jordansimsmith.tcginventory.imports.client.UpdateImportRowRequest;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import java.time.Instant;
import java.util.List;
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
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

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
    assertThat(saved.getSuggestedPrice()).isEqualTo("1.25");
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
