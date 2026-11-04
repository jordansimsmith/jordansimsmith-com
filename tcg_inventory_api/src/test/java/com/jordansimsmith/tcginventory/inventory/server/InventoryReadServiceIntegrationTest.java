package com.jordansimsmith.tcginventory.inventory.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.inventory.client.UpdateSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.UpdateSkuResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

@Testcontainers
class InventoryReadServiceIntegrationTest {
  private static final String TABLE_NAME = "inventory_units_test";

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<InventoryUnitItem> unitTable;
  private static DynamoDbTable<InventorySkuItem> skuTable;
  private static DynamoDbTable<InventoryAuditItem> auditTable;

  private FindUnitsService service;
  private FindSkusService findSkusService;
  private GetSkuService getSkuService;
  private UpdateSkuService updateSkuService;
  private int nextAuditId;

  @BeforeAll
  static void setUpBeforeClass() {
    dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("fake", "fake")))
            .build();
    var enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    skuTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(InventorySkuItem.class));
    unitTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(InventoryUnitItem.class));
    auditTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(InventoryAuditItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, skuTable);
  }

  @AfterAll
  static void tearDownAfterClass() {
    dynamoDbClient.close();
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    service =
        new FindUnitsService(new InventoryReadRepository(skuTable, unitTable), new ObjectMapper());
    var repository = new InventoryReadRepository(skuTable, unitTable);
    findSkusService = new FindSkusService(repository, new ObjectMapper());
    getSkuService = new GetSkuService(repository);
    nextAuditId = 0;
    var mutationRepository =
        new InventoryMutationRepository(
            dynamoDbClient,
            TABLE_NAME,
            () -> Instant.ofEpochSecond(1700000000),
            () -> "audit-" + ++nextAuditId);
    updateSkuService = new UpdateSkuService(repository, mutationRepository);
  }

  @Test
  void updateSkuShouldNormalizePriceAndWriteDirtyVersionAndAuditTogether() throws Exception {
    // arrange
    var item = sku("price-user", "mtg", "priced", "Priced Card", false, "1.00");
    skuTable.putItem(item);
    var skuKey =
        Map.of(
            "pk", AttributeValue.builder().s(item.getPk()).build(),
            "sk", AttributeValue.builder().s("SKU").build());
    dynamoDbClient.updateItem(
        request ->
            request
                .tableName(TABLE_NAME)
                .key(skuKey)
                .updateExpression(
                    "SET unrelated_price = :unrelatedPrice, "
                        + "unrelated_quantity = :unrelatedQuantity")
                .expressionAttributeValues(
                    Map.of(
                        ":unrelatedPrice",
                        AttributeValue.builder().s("2.90").build(),
                        ":unrelatedQuantity",
                        AttributeValue.builder().n("1").build())));

    // act
    var response =
        updateSkuService.updateSku(
            new RequestContext("price-user"),
            new UpdateSkuRequest("priced", new BigDecimal("2.5"), null, null));
    var updated =
        getSkuService.getSku(new RequestContext("price-user"), new GetSkuRequest("priced"));
    var audit =
        auditTable.getItem(
            request ->
                request
                    .key(
                        Key.builder()
                            .partitionValue("USER#price-user#AUDIT")
                            .sortValue("audit-1")
                            .build())
                    .consistentRead(true));

    // assert
    assertThat(response).isEqualTo(new UpdateSkuResponse());
    assertThat(updated.sku().price()).isEqualByComparingTo("2.50");
    assertThat(updated.sku().version()).isEqualTo(2);
    assertThat(
            skuTable
                .getItem(
                    request ->
                        request
                            .key(
                                Key.builder().partitionValue(item.getPk()).sortValue("SKU").build())
                            .consistentRead(true))
                .getGsi1pk())
        .isEqualTo("USER#price-user#DIRTY");
    assertThat(audit.getEventType()).isEqualTo("price_update");
    assertThat(audit.getSkuId()).isEqualTo("priced");
    assertThat(audit.getBeforePrice()).isEqualTo("1.00");
    assertThat(audit.getAfterPrice()).isEqualTo("2.50");
    var rawSku =
        dynamoDbClient
            .getItem(request -> request.tableName(TABLE_NAME).key(skuKey).consistentRead(true))
            .item();
    assertThat(rawSku.get("unrelated_price").s()).isEqualTo("2.90");
    assertThat(rawSku.get("unrelated_quantity").n()).isEqualTo("1");

    // act
    updateSkuService.updateSku(
        new RequestContext("price-user"),
        new UpdateSkuRequest("priced", new BigDecimal("2.50"), null, null));

    // assert
    assertThat(
            getSkuService
                .getSku(new RequestContext("price-user"), new GetSkuRequest("priced"))
                .sku()
                .version())
        .isEqualTo(3);
    assertThat(
            auditTable
                .getItem(
                    request ->
                        request
                            .key(
                                Key.builder()
                                    .partitionValue("USER#price-user#AUDIT")
                                    .sortValue("audit-2")
                                    .build())
                            .consistentRead(true))
                .getBeforePrice())
        .isEqualTo("2.50");
  }

  @Test
  void updateSkuShouldAllowZeroAndRejectNegativeOrOverPrecisionPrices() throws Exception {
    // arrange
    skuTable.putItem(sku("zero-user", "mtg", "priced", "Priced Card", false, "1.00"));

    // act
    updateSkuService.updateSku(
        new RequestContext("zero-user"),
        new UpdateSkuRequest("priced", BigDecimal.ZERO, null, null));

    // assert
    assertThat(
            getSkuService
                .getSku(new RequestContext("zero-user"), new GetSkuRequest("priced"))
                .sku()
                .price())
        .isEqualByComparingTo("0.00");
    assertThatThrownBy(
            () ->
                updateSkuService.updateSku(
                    new RequestContext("zero-user"),
                    new UpdateSkuRequest("priced", new BigDecimal("-0.01"), null, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("price must be nonnegative");
    assertThatThrownBy(
            () ->
                updateSkuService.updateSku(
                    new RequestContext("zero-user"),
                    new UpdateSkuRequest("priced", new BigDecimal("1.001"), null, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("price must have at most two decimal places");
    assertThatThrownBy(
            () ->
                updateSkuService.updateSku(
                    new RequestContext("other-user"),
                    new UpdateSkuRequest("priced", BigDecimal.ONE, null, null)))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");
    assertThatThrownBy(
            () ->
                updateSkuService.updateSku(
                    new RequestContext("zero-user"),
                    new UpdateSkuRequest(" ", BigDecimal.ONE, null, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("sku_id is required");
    assertThat(
            auditTable.getItem(
                request ->
                    request
                        .key(
                            Key.builder()
                                .partitionValue("USER#zero-user#AUDIT")
                                .sortValue("audit-1")
                                .build())
                        .consistentRead(true)))
        .isNotNull();
  }

  @Test
  void updateSkuShouldTreatNullPriceAsAnEmptyPatch() throws Exception {
    // arrange
    skuTable.putItem(sku("empty-user", "mtg", "priced", "Priced Card", false, "1.00"));

    // act
    updateSkuService.updateSku(
        new RequestContext("empty-user"), new UpdateSkuRequest("priced", null, null, null));

    // assert
    var updated =
        getSkuService.getSku(new RequestContext("empty-user"), new GetSkuRequest("priced"));
    assertThat(updated.sku().price()).isEqualByComparingTo("1.00");
    assertThat(updated.sku().version()).isEqualTo(1);
    assertThat(
            auditTable.getItem(
                request ->
                    request
                        .key(
                            Key.builder()
                                .partitionValue("USER#empty-user#AUDIT")
                                .sortValue("audit-1")
                                .build())
                        .consistentRead(true)))
        .isNull();
    assertThatThrownBy(
            () ->
                updateSkuService.updateSku(
                    new RequestContext("empty-user"),
                    new UpdateSkuRequest("missing", null, null, null)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void updateSkuShouldClearDirtyWhenVersionMatchesWithoutChangingVersionOrAudit() throws Exception {
    // arrange
    var item = sku("clear-user", "mtg", "priced", "Priced Card", true, "2.50");
    item.setVersion(5);
    skuTable.putItem(item);
    var key = rawSkuKey("clear-user", "priced");
    dynamoDbClient.updateItem(
        request ->
            request
                .tableName(TABLE_NAME)
                .key(key)
                .updateExpression("SET dirty = :dirty, custom_attribute = :custom")
                .expressionAttributeValues(
                    Map.of(
                        ":dirty", AttributeValue.builder().bool(true).build(),
                        ":custom", AttributeValue.builder().s("preserve").build())));

    // act
    updateSkuService.updateSku(
        new RequestContext("clear-user"), new UpdateSkuRequest("priced", null, false, 5));
    var raw =
        dynamoDbClient.getItem(r -> r.tableName(TABLE_NAME).key(key).consistentRead(true)).item();

    // assert
    assertThat(raw.get("dirty").bool()).isFalse();
    assertThat(raw.get("gsi1pk").s()).isEqualTo("USER#clear-user#CLEAN");
    assertThat(raw.get("version").n()).isEqualTo("5");
    assertThat(raw.get("suggested_price").s()).isEqualTo("2.50");
    assertThat(raw.get("custom_attribute").s()).isEqualTo("preserve");
    assertThat(
            auditTable.getItem(
                r ->
                    r.key(
                            Key.builder()
                                .partitionValue("USER#clear-user#AUDIT")
                                .sortValue("audit-1")
                                .build())
                        .consistentRead(true)))
        .isNull();
  }

  @Test
  void updateSkuShouldMarkDirtyAndInvalidateEarlierAcknowledgement() throws Exception {
    // arrange
    var item = sku("mark-user", "mtg", "priced", "Priced Card", false, "2.50");
    item.setVersion(3);
    skuTable.putItem(item);
    var key = rawSkuKey("mark-user", "priced");
    dynamoDbClient.updateItem(
        request ->
            request
                .tableName(TABLE_NAME)
                .key(key)
                .updateExpression("SET dirty = :clean")
                .expressionAttributeValues(
                    Map.of(":clean", AttributeValue.builder().bool(false).build())));

    // act
    updateSkuService.updateSku(
        new RequestContext("mark-user"), new UpdateSkuRequest("priced", null, true, 3));
    assertThatThrownBy(
            () ->
                updateSkuService.updateSku(
                    new RequestContext("mark-user"),
                    new UpdateSkuRequest("priced", null, false, 3)))
        .isInstanceOf(ConditionalCheckFailedException.class);
    var raw =
        dynamoDbClient.getItem(r -> r.tableName(TABLE_NAME).key(key).consistentRead(true)).item();

    // assert
    assertThat(raw.get("dirty").bool()).isTrue();
    assertThat(raw.get("gsi1pk").s()).isEqualTo("USER#mark-user#DIRTY");
    assertThat(raw.get("gsi1sk").s()).isEqualTo("SKU#priced");
    assertThat(raw.get("version").n()).isEqualTo("4");
    assertThat(raw.get("suggested_price").s()).isEqualTo("2.50");
    assertThat(
            auditTable.getItem(
                r ->
                    r.key(
                            Key.builder()
                                .partitionValue("USER#mark-user#AUDIT")
                                .sortValue("audit-1")
                                .build())
                        .consistentRead(true)))
        .isNull();
  }

  @Test
  void updateSkuShouldTreatMissingVersionAsZeroAndPropagateAlreadyCleanAcknowledgement()
      throws Exception {
    // arrange
    var versionless = sku("versionless-user", "mtg", "priced", "Priced Card", true, "2.50");
    versionless.setVersion(null);
    skuTable.putItem(versionless);
    var key = rawSkuKey("versionless-user", "priced");
    dynamoDbClient.updateItem(
        request ->
            request
                .tableName(TABLE_NAME)
                .key(key)
                .updateExpression("SET dirty = :dirty")
                .expressionAttributeValues(
                    Map.of(":dirty", AttributeValue.builder().bool(true).build())));

    // act
    updateSkuService.updateSku(
        new RequestContext("versionless-user"), new UpdateSkuRequest("priced", null, false, 0));
    assertThatThrownBy(
            () ->
                updateSkuService.updateSku(
                    new RequestContext("versionless-user"),
                    new UpdateSkuRequest("priced", null, false, 0)))
        .isInstanceOf(ConditionalCheckFailedException.class);
    var raw =
        dynamoDbClient.getItem(r -> r.tableName(TABLE_NAME).key(key).consistentRead(true)).item();

    // assert
    assertThat(raw.get("dirty").bool()).isFalse();
    assertThat(raw.get("gsi1pk").s()).isEqualTo("USER#versionless-user#CLEAN");
    assertThat(raw).doesNotContainKey("version");
    assertThatThrownBy(
            () ->
                updateSkuService.updateSku(
                    new RequestContext("another-user"),
                    new UpdateSkuRequest("priced", null, false, 0)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void updateSkuShouldRejectInvalidDirtyPatchBeforeReadingStorage() {
    // arrange
    var inventoryRepository = mock(InventoryReadRepository.class);
    var mutationRepository = mock(InventoryMutationRepository.class);
    var updateService = new UpdateSkuService(inventoryRepository, mutationRepository);
    var ctx = new RequestContext("validation-user");

    // act / assert
    assertThatThrownBy(
            () ->
                updateService.updateSku(ctx, new UpdateSkuRequest("sku", BigDecimal.ONE, true, 1)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("price and dirty cannot be updated together");
    assertThatThrownBy(
            () -> updateService.updateSku(ctx, new UpdateSkuRequest("sku", null, false, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("version is required when updating dirty state");
    assertThatThrownBy(
            () -> updateService.updateSku(ctx, new UpdateSkuRequest("sku", null, null, 1)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("version requires a dirty update");
    verifyNoInteractions(inventoryRepository, mutationRepository);
  }

  @Test
  void updateSkuShouldPropagateStorageFailuresForDirtyChanges() throws Exception {
    // arrange
    skuTable.putItem(sku("failure-user", "mtg", "priced", "Priced Card", false, "2.50"));
    var repository = new InventoryReadRepository(skuTable, unitTable);
    var mutationRepository = mock(InventoryMutationRepository.class);
    var failure = new IllegalStateException("storage unavailable");
    doThrow(failure).when(mutationRepository).markSkuDirty("failure-user", "priced", 1);
    var updateService = new UpdateSkuService(repository, mutationRepository);

    // act / assert
    assertThatThrownBy(
            () ->
                updateService.updateSku(
                    new RequestContext("failure-user"),
                    new UpdateSkuRequest("priced", null, true, 1)))
        .isSameAs(failure);
  }

  @Test
  void updateSkuRequestShouldDeserializeDirtyFieldsAndResponseShouldRemainEmpty() throws Exception {
    // arrange
    var objectMapper = new ObjectMapper();

    // act
    var request =
        objectMapper.readValue(
            "{\"sku_id\":\"sku\",\"dirty\":false,\"version\":5}", UpdateSkuRequest.class);

    // assert
    assertThat(request).isEqualTo(new UpdateSkuRequest("sku", null, false, 5));
    assertThat(objectMapper.writeValueAsString(new UpdateSkuResponse())).isEqualTo("{}");
  }

  @Test
  void findSkusShouldFilterSortAndPaginateByAuthenticatedUser() throws Exception {
    // arrange
    skuTable.putItem(sku("alice", "alpha", "Alpha Card", "2.50"));
    skuTable.putItem(sku("alice", "beta", "Beta Card", null));
    skuTable.putItem(sku("alice", "else", "Other Card", "2"));
    skuTable.putItem(sku("bob", "alpha", "Alpha Card", "9"));

    // act
    var firstPage =
        findSkusService.findSkus(
            new RequestContext("alice"), new FindSkusRequest("mtg", null, null, 1, null));
    var nextPage =
        findSkusService.findSkus(
            new RequestContext("alice"),
            new FindSkusRequest("mtg", null, null, 1, firstPage.nextContinuation()));
    var finalPage =
        findSkusService.findSkus(
            new RequestContext("alice"),
            new FindSkusRequest("mtg", null, null, 1, nextPage.nextContinuation()));
    var emptyPage =
        findSkusService.findSkus(
            new RequestContext("alice"),
            new FindSkusRequest("mtg", null, null, 1, finalPage.nextContinuation()));
    var bobPage =
        findSkusService.findSkus(
            new RequestContext("bob"), new FindSkusRequest("mtg", null, null, 20, null));

    // assert
    assertThat(firstPage.skus()).hasSize(1);
    assertThat(firstPage.skus().get(0).name()).isEqualTo("Alpha Card");
    assertThat(firstPage.skus().get(0).externalId()).isEqualTo("alpha");
    assertThat(firstPage.skus().get(0).price()).isEqualByComparingTo("2.50");
    assertThat(nextPage.skus()).extracting(sku -> sku.name()).containsExactly("Beta Card");
    assertThat(finalPage.skus()).extracting(sku -> sku.name()).containsExactly("Other Card");
    assertThat(emptyPage.skus()).isEmpty();
    assertThat(emptyPage.nextContinuation()).isNull();
    assertThat(bobPage.skus()).extracting(sku -> sku.name()).containsExactly("Alpha Card");
  }

  @Test
  void findSkusShouldUseCaseInsensitiveNamePrefix() throws Exception {
    // arrange
    skuTable.putItem(sku("alice", "alpha", "Alpha Card", null));
    skuTable.putItem(sku("alice", "alpine", "Alpine Card", null));
    skuTable.putItem(sku("alice", "beta", "Beta Card", null));

    // act
    var response =
        findSkusService.findSkus(
            new RequestContext("alice"), new FindSkusRequest("mtg", "aLp", null, null, null));

    // assert
    assertThat(response.skus())
        .extracting(sku -> sku.name())
        .containsExactly("Alpha Card", "Alpine Card");
  }

  @Test
  void findSkusShouldSupportUserWideBrowseAndExclusiveDirtyMode() throws Exception {
    // arrange
    skuTable.putItem(sku("alice", "mtg", "alpha", "Alpha Card", true));
    skuTable.putItem(sku("alice", "pokemon", "beta", "Beta Card", true));
    skuTable.putItem(sku("alice", "mtg", "clean", "Clean Card", false));
    skuTable.putItem(sku("bob", "mtg", "other", "Other Card", true));

    // act
    var allSkus =
        findSkusService.findSkus(
            new RequestContext("alice"), new FindSkusRequest(null, null, null, 100, null));
    var dirtyPage =
        findSkusService.findSkus(
            new RequestContext("alice"), new FindSkusRequest(null, null, true, 1, null));
    var nextDirtyPage =
        findSkusService.findSkus(
            new RequestContext("alice"),
            new FindSkusRequest(null, null, true, 1, dirtyPage.nextContinuation()));
    var cleanPage =
        findSkusService.findSkus(
            new RequestContext("alice"), new FindSkusRequest(null, null, false, 100, null));

    // assert
    assertThat(allSkus.skus())
        .extracting(sku -> sku.game())
        .containsExactly("mtg", "mtg", "pokemon");
    assertThat(dirtyPage.skus()).extracting(sku -> sku.skuId()).containsExactly("alpha");
    assertThat(dirtyPage.nextContinuation()).isNotNull();
    assertThat(nextDirtyPage.skus()).extracting(sku -> sku.skuId()).containsExactly("beta");
    assertThat(cleanPage.skus()).extracting(sku -> sku.skuId()).containsExactly("clean");
  }

  @Test
  void findSkusShouldReturnWorkerFactsFromStorage() throws Exception {
    // arrange
    var item = sku("alice", "mtg", "alpha", "Alpha Card", true);
    item.setSuggestedPrice("2.50");
    item.setFetchtcgCardId("fetch-card");
    item.setFetchtcgSetId(123);
    item.setVersion(7);
    skuTable.putItem(item);
    var versionless = sku("alice", "mtg", "versionless", "Versionless Card", false);
    versionless.setVersion(null);
    versionless.setSuggestedPrice("0.00");
    skuTable.putItem(versionless);

    // act
    var listed =
        findSkusService.findSkus(
            new RequestContext("alice"), new FindSkusRequest("mtg", null, null, null, null));
    var fetched =
        getSkuService.getSku(new RequestContext("alice"), new GetSkuRequest("alpha")).sku();
    var oldSku =
        getSkuService.getSku(new RequestContext("alice"), new GetSkuRequest("versionless")).sku();

    // assert
    assertThat(listed.skus().getFirst().price()).isEqualByComparingTo("2.50");
    assertThat(listed.skus().getFirst().fetchtcgCardId()).isEqualTo("fetch-card");
    assertThat(listed.skus().getFirst().fetchtcgSetId()).isEqualTo(123);
    assertThat(listed.skus().getFirst().version()).isEqualTo(7);
    assertThat(fetched).isEqualTo(listed.skus().getFirst());
    assertThat(oldSku.version()).isZero();
    assertThat(oldSku.price()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void skuReadServicesShouldRejectInvalidInputsBeforeStorageAccess() {
    // arrange
    var inventoryRepository = mock(InventoryReadRepository.class);
    var findService = new FindSkusService(inventoryRepository, new ObjectMapper());
    var getService = new GetSkuService(inventoryRepository);

    // act / assert
    assertThatThrownBy(
            () ->
                findService.findSkus(
                    new RequestContext("alice"), new FindSkusRequest(" ", null, null, null, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("game is required");
    assertThatThrownBy(
            () ->
                findService.findSkus(
                    new RequestContext("alice"), new FindSkusRequest("mtg", null, null, 101, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("limit must be between 1 and 100");
    assertThatThrownBy(
            () ->
                findService.findSkus(
                    new RequestContext("alice"), new FindSkusRequest("mtg", null, null, null, "%")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("Invalid continuation");
    assertThatThrownBy(() -> getService.getSku(new RequestContext("alice"), new GetSkuRequest(" ")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("sku_id is required");
    verifyNoInteractions(inventoryRepository);
  }

  @Test
  void findSkusShouldRejectCombinedSearchModesBeforeStorageAccess() {
    // arrange
    var inventoryRepository = mock(InventoryReadRepository.class);
    var findService = new FindSkusService(inventoryRepository, new ObjectMapper());

    // act / assert
    assertThatThrownBy(
            () ->
                findService.findSkus(
                    new RequestContext("alice"),
                    new FindSkusRequest("mtg", null, true, null, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("dirty cannot be combined with game or search");
    assertThatThrownBy(
            () ->
                findService.findSkus(
                    new RequestContext("alice"), new FindSkusRequest(null, "", false, null, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("dirty cannot be combined with game or search");
    assertThatThrownBy(
            () ->
                findService.findSkus(
                    new RequestContext("alice"),
                    new FindSkusRequest(null, "alpha", null, null, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("search requires game");
    verifyNoInteractions(inventoryRepository);
  }

  @Test
  void getSkuShouldReturnNumericZeroAndMissingPricesAndRejectUnknownSku() throws Exception {
    // arrange
    skuTable.putItem(sku("alice", "zero", "Zero Price", "0.00"));
    skuTable.putItem(sku("alice", "missing", "Missing Price", null));

    // act
    var zero = getSkuService.getSku(new RequestContext("alice"), new GetSkuRequest("zero")).sku();
    var missing =
        getSkuService.getSku(new RequestContext("alice"), new GetSkuRequest("missing")).sku();

    // assert
    assertThat(zero.price()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(missing.price()).isNull();
    assertThatThrownBy(
            () -> getSkuService.getSku(new RequestContext("bob"), new GetSkuRequest("zero")))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");
    assertThatThrownBy(
            () -> getSkuService.getSku(new RequestContext("alice"), new GetSkuRequest("bad")))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void malformedStoredPriceShouldFailVisibly() {
    // arrange
    skuTable.putItem(sku("alice", "bad-price", "Bad Price", "not-a-number"));

    // act / assert
    assertThatThrownBy(
            () -> getSkuService.getSku(new RequestContext("alice"), new GetSkuRequest("bad-price")))
        .isInstanceOf(NumberFormatException.class);
  }

  @Test
  void findUnitsShouldPageInSequenceAndReturnOrderedPhotoIds() throws Exception {
    // arrange
    unitTable.putItem(unit("alice", "sku", 1, "removed"));
    var reserved = unit("alice", "sku", 2, "reserved", List.of(photo("front"), photo("back")));
    reserved.setCreatedAt(Instant.ofEpochSecond(1700000000));
    reserved.setUpdatedAt(Instant.ofEpochSecond(1700000001));
    reserved.setOrderId("order-1");
    unitTable.putItem(reserved);
    var sold = unit("alice", "sku", 3, "sold");
    sold.setOrderId("order-2");
    unitTable.putItem(sold);
    unitTable.putItem(unit("bob", "sku", 2, "in_stock"));

    // act
    var firstPage =
        service.findUnits(new RequestContext("alice"), new FindUnitsRequest("sku", 1, null));
    var secondPage =
        service.findUnits(
            new RequestContext("alice"),
            new FindUnitsRequest("sku", 1, firstPage.nextContinuation()));
    var allAliceUnits =
        service.findUnits(new RequestContext("alice"), new FindUnitsRequest("sku", 100, null));
    var bobPage =
        service.findUnits(new RequestContext("bob"), new FindUnitsRequest("sku", null, null));

    // assert
    assertThat(firstPage.units()).isEmpty();
    assertThat(firstPage.nextContinuation()).isNotNull();
    assertThat(secondPage.units()).hasSize(1);
    assertThat(secondPage.units().getFirst().sequenceNumber()).isEqualTo(2);
    assertThat(secondPage.units().getFirst().location()).isEqualTo("A0-2");
    assertThat(secondPage.units().getFirst().status()).isEqualTo(UnitStatus.RESERVED);
    assertThat(secondPage.units().getFirst().photos()).containsExactly("front", "back");
    assertThat(secondPage.units().getFirst().createdAt()).isEqualTo(1700000000L);
    assertThat(secondPage.units().getFirst().updatedAt()).isEqualTo(1700000001L);
    assertThat(secondPage.units().getFirst().reservationId()).isEqualTo("order-1");
    assertThat(secondPage.nextContinuation()).isNotNull();
    assertThat(allAliceUnits.units())
        .extracting("status")
        .containsExactly(UnitStatus.RESERVED, UnitStatus.SOLD);
    assertThat(allAliceUnits.units())
        .extracting("reservationId")
        .containsExactly("order-1", "order-2");
    assertThat(bobPage.units()).extracting("sequenceNumber").containsExactly(2);
    assertThat(bobPage.units().getFirst().photos()).isEmpty();
    assertThat(bobPage.units().getFirst().createdAt()).isEqualTo(1700000000L);
    assertThat(bobPage.units().getFirst().updatedAt()).isNull();
    var objectMapper = new ObjectMapper();
    assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(secondPage)))
        .isEqualTo(
            objectMapper.readTree(
                """
                {"units":[{"sequence_number":2,"location":"A0-2","status":"reserved","photos":["front","back"],"created_at":1700000000,"updated_at":1700000001,"reservation_id":"order-1"}],"next_continuation":"%s"}
                """
                    .formatted(secondPage.nextContinuation())));
  }

  @Test
  void findUnitsShouldFailOnUnknownStoredStatus() {
    // arrange
    unitTable.putItem(unit("alice", "sku", 1, "unexpected"));

    // act / assert
    assertThatThrownBy(
            () ->
                service.findUnits(
                    new RequestContext("alice"), new FindUnitsRequest("sku", 100, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void findUnitsShouldReturnEmptyForUnknownPartition() throws Exception {
    // act
    var response =
        service.findUnits(new RequestContext("alice"), new FindUnitsRequest("missing", null, null));

    // assert
    assertThat(response.units()).isEmpty();
    assertThat(response.nextContinuation()).isNull();
  }

  @Test
  void findUnitsShouldReturnSafeErrorsForInvalidArgumentsAndContinuation() {
    // arrange / act / assert
    assertThatThrownBy(
            () ->
                service.findUnits(
                    new RequestContext("alice"), new FindUnitsRequest(" ", null, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("sku_id is required");
    assertThatThrownBy(
            () ->
                service.findUnits(
                    new RequestContext("alice"), new FindUnitsRequest("sku", 0, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("limit must be between 1 and 100");
    assertThatThrownBy(
            () ->
                service.findUnits(
                    new RequestContext("alice"), new FindUnitsRequest("sku", null, "%")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("Invalid continuation");
  }

  @Test
  void findUnitsShouldRejectInvalidLimitBeforeStorageAccess() {
    // arrange
    var inventoryRepository = mock(InventoryReadRepository.class);
    var invalidLimitService = new FindUnitsService(inventoryRepository, new ObjectMapper());

    // act / assert
    assertThatThrownBy(
            () ->
                invalidLimitService.findUnits(
                    new RequestContext("alice"), new FindUnitsRequest("sku", 101, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("limit must be between 1 and 100");
    verifyNoInteractions(inventoryRepository);
  }

  @Test
  void findUnitsShouldPropagateStorageFailure() {
    // arrange
    var inventoryRepository = mock(InventoryReadRepository.class);
    var failure = new IllegalStateException("storage failed");
    when(inventoryRepository.findUnits(eq("alice"), eq("sku"), eq(100), isNull()))
        .thenThrow(failure);
    var failingService = new FindUnitsService(inventoryRepository, new ObjectMapper());

    // act / assert
    assertThatThrownBy(
            () ->
                failingService.findUnits(
                    new RequestContext("alice"), new FindUnitsRequest("sku", null, null)))
        .isSameAs(failure);
    verify(inventoryRepository).findUnits(eq("alice"), eq("sku"), eq(100), isNull());
  }

  private static InventoryUnitItem unit(
      String user, String skuId, int sequenceNumber, String status) {
    return unit(user, skuId, sequenceNumber, status, null);
  }

  private static InventoryUnitItem unit(
      String user,
      String skuId,
      int sequenceNumber,
      String status,
      List<InventoryUnitItem.PhotoItem> photos) {
    var item = new InventoryUnitItem();
    item.setPk(InventoryUnitItem.formatPk(user, skuId));
    item.setSk(InventoryUnitItem.UNIT_PREFIX + "%010d".formatted(sequenceNumber));
    item.setSequenceNumber(sequenceNumber);
    item.setStatus(status);
    item.setCreatedAt(Instant.ofEpochSecond(1700000000));
    item.setPhotos(photos);
    return item;
  }

  private static InventoryUnitItem.PhotoItem photo(String photoId) {
    var item = new InventoryUnitItem.PhotoItem();
    item.setPhotoId(photoId);
    return item;
  }

  private static Map<String, AttributeValue> rawSkuKey(String user, String skuId) {
    return Map.of(
        "pk", AttributeValue.builder().s(InventorySkuItem.formatPk(user, skuId)).build(),
        "sk", AttributeValue.builder().s("SKU").build());
  }

  private static InventorySkuItem sku(String user, String skuId, String name, String price) {
    return sku(user, "mtg", skuId, name, true, price);
  }

  private static InventorySkuItem sku(
      String user, String game, String skuId, String name, boolean dirty) {
    return sku(user, game, skuId, name, dirty, null);
  }

  private static InventorySkuItem sku(
      String user, String game, String skuId, String name, boolean dirty, String price) {
    var item = new InventorySkuItem();
    item.setPk(InventorySkuItem.formatPk(user, skuId));
    item.setSk("SKU");
    item.setSkuId(skuId);
    item.setGame(game);
    item.setExternalId(skuId);
    item.setName(name);
    item.setSetCode("set");
    item.setSetName("Set");
    item.setCollectorNumber("1");
    item.setFinish("normal");
    item.setCondition("NM");
    item.setSuggestedPrice(price);
    item.setFetchtcgCardId("fetch-" + skuId);
    item.setFetchtcgSetId(1);
    item.setVersion(1);
    item.setGsi1pk(InventorySkuItem.formatGsi1pk(user, dirty));
    item.setGsi1sk(InventorySkuItem.formatGsi1skPrefix() + skuId);
    item.setGsi2pk(InventorySkuItem.formatGsi2pk(user));
    item.setGsi2sk(InventorySkuItem.formatGsi2skPrefix(game, name.toLowerCase()) + "#" + skuId);
    return item;
  }
}
