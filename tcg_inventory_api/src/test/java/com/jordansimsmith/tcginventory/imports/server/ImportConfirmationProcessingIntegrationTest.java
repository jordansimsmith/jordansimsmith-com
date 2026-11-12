package com.jordansimsmith.tcginventory.imports.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.CreateUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.FakeInventoryService;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetUnitRequest;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
class ImportConfirmationProcessingIntegrationTest {
  private static final String TABLE_NAME = "tcg_inventory";
  private static final String USER = "confirmation-user";
  private static final String IMPORT_ID = "opaque/import-id";
  private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<ImportItem> importTable;
  private static DynamoDbTable<ImportRowItem> importRowTable;
  private static ImportReadRepository reads;
  private FakeClock clock;
  private ImportMutationRepository writes;
  private ImportConfirmationMessageHandler handler;
  private FakeInventoryService inventory;

  @BeforeAll
  static void createTable() {
    dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    importTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(ImportItem.class));
    importRowTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(ImportRowItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, importTable);
    reads = new ImportReadRepository(importTable, importRowTable);
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    clock = new FakeClock();
    clock.setTime(NOW);
    writes = new ImportMutationRepository(dynamoDbClient, TABLE_NAME);
    inventory = new FakeInventoryService(new FakeGamesService());
    handler = new ImportConfirmationMessageHandler(reads, writes, inventory, clock);
  }

  @Test
  void handlerCreatesOnlyKeepersAndStoresInventoryPlacements() throws Exception {
    putImport(4);
    importRowTable.putItem(row(1, "keep", "card-a", "normal"));
    importRowTable.putItem(row(2, "discard", "card-a", "normal"));
    importRowTable.putItem(row(4, "keep", "card-b", "foil"));

    handler.handleMessage(
        new RequestContext(USER),
        new ImportMessage("ignored-payload-user", IMPORT_ID, ImportMessageType.CONFIRM));

    var confirmedImport = reads.getImport(USER, IMPORT_ID);
    assertThat(confirmedImport.getStatus()).isEqualTo("confirmed");
    assertThat(confirmedImport.getUpdatedAt()).isEqualTo(NOW);
    var first = reads.getRow(USER, IMPORT_ID, 1);
    var discarded = reads.getRow(USER, IMPORT_ID, 2);
    var last = reads.getRow(USER, IMPORT_ID, 4);
    assertThat(first.getSkuId()).isEqualTo("mtg#scryfall#card-a#normal#NM");
    assertThat(first.getSequenceNumber()).isZero();
    assertThat(first.getLocation()).isEqualTo("A0-0");
    assertThat(discarded.getSkuId()).isNull();
    assertThat(last.getSkuId()).isEqualTo("mtg#scryfall#card-b#foil#NM");
    assertThat(last.getSequenceNumber()).isEqualTo(1);
    assertThat(
            inventory
                .getUnit(
                    new RequestContext(USER),
                    new GetUnitRequest(first.getSkuId(), first.getSequenceNumber()))
                .unit()
                .photos())
        .containsExactly("photo-first", "photo-second");
  }

  @Test
  void retryRecoversInventoryReceiptAfterProgressWriteFailure() throws Exception {
    putImport(1);
    importRowTable.putItem(row(1, "keep", "card-a", "normal"));
    var failOnce = new AtomicBoolean(true);
    var failingWrites =
        new ImportMutationRepository(dynamoDbClient, TABLE_NAME) {
          @Override
          public void recordCreatedUnit(
              String user,
              String importId,
              int position,
              String skuId,
              int sequenceNumber,
              String location) {
            if (failOnce.getAndSet(false)) {
              throw new IllegalStateException("progress write failed");
            }
            super.recordCreatedUnit(user, importId, position, skuId, sequenceNumber, location);
          }
        };
    var failingHandler =
        new ImportConfirmationMessageHandler(reads, failingWrites, inventory, clock);
    var ctx = new RequestContext(USER);
    var message = new ImportMessage(USER, IMPORT_ID, ImportMessageType.CONFIRM);

    assertThatThrownBy(() -> failingHandler.handleMessage(ctx, message))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("progress write failed");
    assertThat(reads.getRow(USER, IMPORT_ID, 1).getSkuId()).isNull();

    handler.handleMessage(ctx, message);

    var saved = reads.getRow(USER, IMPORT_ID, 1);
    assertThat(saved.getSkuId()).isEqualTo("mtg#scryfall#card-a#normal#NM");
    assertThat(saved.getSequenceNumber()).isZero();
    assertThat(reads.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("confirmed");

    assertThat(
            inventory
                .getSku(ctx, new GetSkuRequest("mtg#scryfall#card-a#normal#NM"))
                .sku()
                .version())
        .isEqualTo(1);
    var next = inventory.createUnit(ctx, request("next-operation", "card-a", "normal"));
    assertThat(next.sequenceNumber()).isEqualTo(1);
  }

  @Test
  void retryAfterCompletionWriteFailureUsesSavedRowProgress() throws Exception {
    putImport(1);
    importRowTable.putItem(row(1, "keep", "card-a", "normal"));
    var failOnce = new AtomicBoolean(true);
    var failingWrites =
        new ImportMutationRepository(dynamoDbClient, TABLE_NAME) {
          @Override
          public void finishConfirmation(String user, String importId, Instant now) {
            if (failOnce.getAndSet(false)) {
              throw new IllegalStateException("completion write failed");
            }
            super.finishConfirmation(user, importId, now);
          }
        };
    var failingHandler =
        new ImportConfirmationMessageHandler(reads, failingWrites, inventory, clock);
    var ctx = new RequestContext(USER);
    var message = new ImportMessage(USER, IMPORT_ID, ImportMessageType.CONFIRM);

    assertThatThrownBy(() -> failingHandler.handleMessage(ctx, message))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("completion write failed");
    assertThat(reads.getRow(USER, IMPORT_ID, 1).getSkuId())
        .isEqualTo("mtg#scryfall#card-a#normal#NM");
    assertThat(
            inventory
                .getSku(ctx, new GetSkuRequest("mtg#scryfall#card-a#normal#NM"))
                .sku()
                .version())
        .isEqualTo(1);

    handler.handleMessage(ctx, message);

    assertThat(reads.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("confirmed");
    assertThat(
            inventory
                .getSku(ctx, new GetSkuRequest("mtg#scryfall#card-a#normal#NM"))
                .sku()
                .version())
        .isEqualTo(1);
  }

  @Test
  void handlerReadsEveryPageForImportsLargerThanOneHundredRows() throws Exception {
    putImport(120);
    for (int position = 1; position <= 120; position++) {
      var row = row(position, "keep", "card-a", "normal");
      row.setName("x".repeat(10_000));
      importRowTable.putItem(row);
    }

    handler.handleMessage(
        new RequestContext(USER), new ImportMessage(USER, IMPORT_ID, ImportMessageType.CONFIRM));

    assertThat(reads.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("confirmed");
    assertThat(reads.getRow(USER, IMPORT_ID, 120).getSequenceNumber()).isEqualTo(119);
  }

  @Test
  void handlerCompletesAnEmptyImportWithoutCreatingInventoryUnits() throws Exception {
    putImport(0);

    handler.handleMessage(
        new RequestContext(USER), new ImportMessage(USER, IMPORT_ID, ImportMessageType.CONFIRM));

    assertThat(reads.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("confirmed");
  }

  @Test
  void handlerCompletesWhenNoRowsAreKept() throws Exception {
    putImport(2);
    importRowTable.putItem(row(1, "discard", "discarded-card", "normal"));
    importRowTable.putItem(row(2, "review", "review-card", "normal"));

    handler.handleMessage(
        new RequestContext(USER), new ImportMessage(USER, IMPORT_ID, ImportMessageType.CONFIRM));

    assertThat(reads.getImport(USER, IMPORT_ID).getStatus()).isEqualTo("confirmed");
    assertThat(reads.getRow(USER, IMPORT_ID, 1).getSkuId()).isNull();
    assertThat(reads.getRow(USER, IMPORT_ID, 2).getSkuId()).isNull();
  }

  @Test
  void repositoryWritesOnlyTargetFieldsAndRejectsMissingRowsAndInvalidTransitions() {
    putImport(1);
    assertThatThrownBy(() -> writes.recordCreatedUnit(USER, IMPORT_ID, 1, "sku", 0, "A0-0"))
        .isInstanceOf(RuntimeException.class);
    importRowTable.putItem(row(1, "keep", "card-a", "normal"));
    dynamoDbClient.updateItem(
        request ->
            request
                .tableName(TABLE_NAME)
                .key(
                    Map.of(
                        ImportRowItem.PK,
                        AttributeValue.fromS(ImportRowItem.formatPk(USER, IMPORT_ID)),
                        ImportRowItem.SK,
                        AttributeValue.fromS(ImportRowItem.formatSk(1))))
                .updateExpression("SET unrelated = :value")
                .expressionAttributeValues(Map.of(":value", AttributeValue.fromS("kept"))));

    writes.recordCreatedUnit(USER, IMPORT_ID, 1, "sku", 0, "A0-0");
    var saved = reads.getRow(USER, IMPORT_ID, 1);
    assertThat(saved.getSkuId()).isEqualTo("sku");
    assertThat(
            dynamoDbClient
                .getItem(
                    request ->
                        request
                            .tableName(TABLE_NAME)
                            .key(
                                Map.of(
                                    ImportRowItem.PK,
                                    AttributeValue.fromS(ImportRowItem.formatPk(USER, IMPORT_ID)),
                                    ImportRowItem.SK,
                                    AttributeValue.fromS(ImportRowItem.formatSk(1))))
                            .consistentRead(true))
                .item())
        .containsKey("unrelated");
    assertThatThrownBy(() -> writes.recordCreatedUnit(USER, IMPORT_ID, 1, "sku2", 1, "A0-1"))
        .isInstanceOf(RuntimeException.class);
    var importItem = reads.getImport(USER, IMPORT_ID);
    assertThat(importItem.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1_699_999_999));
    importItem.setStatus("review");
    importTable.putItem(importItem);
    assertThatThrownBy(() -> writes.finishConfirmation(USER, IMPORT_ID, NOW))
        .isInstanceOf(RuntimeException.class);
  }

  private static void putImport(int rowCount) {
    var item = new ImportItem();
    item.setPk(ImportItem.formatPk(USER));
    item.setSk(ImportItem.formatSk(IMPORT_ID));
    item.setImportId(IMPORT_ID);
    item.setGame("mtg");
    item.setStatus("confirming");
    item.setRowCount(rowCount);
    item.setCreatedAt(NOW);
    item.setUpdatedAt(Instant.ofEpochSecond(1_699_999_999));
    importTable.putItem(item);
  }

  private static ImportRowItem row(
      int position, String decision, String externalId, String finish) {
    var item = new ImportRowItem();
    item.setPk(ImportRowItem.formatPk(USER, IMPORT_ID));
    item.setSk(ImportRowItem.formatSk(position));
    item.setPosition(position);
    item.setName("Card " + externalId);
    item.setSetCode("set");
    item.setSetName("Set");
    item.setCollectorNumber(Integer.toString(position));
    item.setFinish(finish);
    item.setCondition("NM");
    item.setExternalId(externalId);
    item.setLanguage("en");
    item.setDecision(decision);
    item.setSuggestedPrice("1.00");
    item.setFetchtcgSetId(42);
    item.setPhotos(
        List.of(
            ImportRowItem.Photo.create("photo-first"), ImportRowItem.Photo.create("photo-second")));
    return item;
  }

  private static CreateUnitRequest request(String operationId, String externalId, String finish) {
    return new CreateUnitRequest(
        operationId,
        "mtg",
        externalId,
        finish,
        Condition.NM,
        "Card " + externalId,
        "set",
        "Set",
        "1",
        new BigDecimal("1.00"),
        "fetch-" + externalId,
        42,
        List.of());
  }
}
