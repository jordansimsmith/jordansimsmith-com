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
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.CreateUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindSkusRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.ReleaseUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.ReleaseUnitResponse;
import com.jordansimsmith.tcginventory.inventory.client.ReserveUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.SellUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.SellUnitResponse;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.inventory.client.UpdateSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.UpdateSkuResponse;
import com.jordansimsmith.tcginventory.inventory.client.UpdateUnitRequest;
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
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

@Testcontainers
class InventoryReadServiceIntegrationTest {
  private static final String TABLE_NAME = "inventory_units_test";

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<InventoryUnitItem> unitTable;
  private static DynamoDbTable<InventorySkuItem> skuTable;
  private static DynamoDbTable<InventoryAuditItem> auditTable;
  private static DynamoDbTable<InventoryCreateUnitOperationItem> createUnitOperationTable;
  private static DynamoDbTable<InventoryReserveUnitOperationItem> reserveUnitOperationTable;
  private static DynamoDbTable<InventoryReservationItem> reservationTable;

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
    createUnitOperationTable =
        enhancedClient.table(
            TABLE_NAME, TableSchema.fromBean(InventoryCreateUnitOperationItem.class));
    reserveUnitOperationTable =
        enhancedClient.table(
            TABLE_NAME, TableSchema.fromBean(InventoryReserveUnitOperationItem.class));
    reservationTable =
        enhancedClient.table(TABLE_NAME, TableSchema.fromBean(InventoryReservationItem.class));
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
            createUnitOperationTable,
            reserveUnitOperationTable,
            reservationTable,
            TABLE_NAME,
            () -> Instant.ofEpochSecond(1700000000),
            () -> "audit-" + ++nextAuditId);
    updateSkuService = new UpdateSkuService(repository, mutationRepository);
  }

  @Test
  void reserveUnitShouldSelectAcrossPagesAndReplayOriginalSelection() throws Exception {
    // arrange
    var enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    var allocationTable =
        enhanced.table(TABLE_NAME, TableSchema.fromBean(InventoryReserveUnitOperationItem.class));
    var reservationTable =
        enhanced.table(TABLE_NAME, TableSchema.fromBean(InventoryReservationItem.class));
    var mutations =
        new InventoryMutationRepository(
            dynamoDbClient,
            createUnitOperationTable,
            allocationTable,
            reservationTable,
            TABLE_NAME,
            () -> Instant.ofEpochSecond(1700000010),
            () -> "reservation-audit");
    var reads = new InventoryReadRepository(skuTable, unitTable);
    var service = new ReserveUnitService(reads, mutations);
    skuTable.putItem(sku("alice", "sku", "Card", "1.00"));
    for (int i = 0; i < 100; i++) unitTable.putItem(unit("alice", "sku", i, "sold"));
    unitTable.putItem(unit("alice", "sku", 100, "in_stock", List.of(photo("front"))));
    unitTable.putItem(unit("alice", "sku", 101, "in_stock"));
    var ctx = new RequestContext("alice");

    // act
    var response = service.reserveUnit(ctx, new ReserveUnitRequest("allocation", "order", "sku"));

    // assert
    assertThat(response.sequenceNumber()).isEqualTo(100);
    var reserved = reads.getUnit("alice", "sku", 100);
    assertThat(reserved.getStatus()).isEqualTo("reserved");
    assertThat(reserved.getReservationId()).isEqualTo("order");
    var storedUnit =
        dynamoDbClient
            .getItem(
                request ->
                    request
                        .tableName(TABLE_NAME)
                        .key(
                            Map.of(
                                "pk",
                                    AttributeValue.builder()
                                        .s(InventoryUnitItem.formatPk("alice", "sku"))
                                        .build(),
                                "sk",
                                    AttributeValue.builder()
                                        .s(InventoryUnitItem.formatSk(100))
                                        .build())))
            .item();
    assertThat(storedUnit.get("reservation_id").s()).isEqualTo("order");
    assertThat(storedUnit).doesNotContainKey("order_id");
    assertThat(reserved.getCreatedAt()).isEqualTo(Instant.ofEpochSecond(1700000000));
    assertThat(reserved.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1700000010));
    assertThat(reserved.getPhotos())
        .extracting(InventoryUnitItem.PhotoItem::getPhotoId)
        .containsExactly("front");
    assertThat(reads.getSku("alice", "sku").getVersion()).isEqualTo(2);
    assertThat(reads.getSku("alice", "sku").getGsi1pk())
        .isEqualTo(InventorySkuItem.formatGsi1pk("alice", true));
    assertThat(
            auditTable.scan().items().stream().filter(item -> item.getEventType() != null).toList())
        .singleElement()
        .satisfies(
            audit -> {
              assertThat(audit.getEventType()).isEqualTo("reserve");
              assertThat(audit.getReservationId()).isEqualTo("order");
            });
    var lifecycle =
        reservationTable.getItem(
            Key.builder()
                .partitionValue("USER#alice#INVENTORY")
                .sortValue("RESERVATION#order#SKU#sku#UNIT#100")
                .build());
    assertThat(lifecycle.getStatus()).isEqualTo("reserved");
    assertThat(lifecycle.getReservationId()).isEqualTo("order");
    assertThat(mutations.getReserveUnitOperation("alice", "allocation").getSkuId())
        .isEqualTo("sku");
    assertThat(mutations.getReserveUnitOperation("bob", "allocation")).isNull();

    // arrange
    reserved.setStatus("sold");
    unitTable.putItem(reserved);

    // act / assert
    assertThat(service.reserveUnit(ctx, new ReserveUnitRequest("allocation", null, null)))
        .isEqualTo(response);
    assertThat(reads.getUnit("alice", "sku", 101).getStatus()).isEqualTo("in_stock");
    assertThat(reads.getSku("alice", "sku").getVersion()).isEqualTo(2);
    assertThat(
            auditTable.scan().items().stream().filter(item -> item.getEventType() != null).toList())
        .hasSize(1);
    assertThat(new ObjectMapper().writeValueAsString(response))
        .isEqualTo("{\"sequence_number\":100}");
    assertThat(
            new ObjectMapper()
                .readValue(
                    "{\"operation_id\":\"a\",\"reservation_id\":\"r\",\"sku_id\":\"s\"}",
                    ReserveUnitRequest.class))
        .isEqualTo(new ReserveUnitRequest("a", "r", "s"));
  }

  @Test
  void releaseUnitShouldBeReplaySafeAfterAnotherReservationTakesTheUnit() throws Exception {
    // arrange
    var mutations =
        new InventoryMutationRepository(
            dynamoDbClient,
            createUnitOperationTable,
            reserveUnitOperationTable,
            reservationTable,
            TABLE_NAME,
            () -> Instant.ofEpochSecond(1700000050),
            () -> "release-audit-" + ++nextAuditId);
    var reads = new InventoryReadRepository(skuTable, unitTable);
    var service = new ReleaseUnitService(reads, mutations);
    skuTable.putItem(sku("alice", "sku", "Card", "1.00"));
    var reserved = unit("alice", "sku", 1, "reserved", List.of(photo("front")));
    reserved.setReservationId("reservation-a");
    reserved.setUpdatedAt(Instant.ofEpochSecond(1700000010));
    unitTable.putItem(reserved);
    reservationTable.putItem(
        InventoryReservationItem.create("alice", "reservation-a", "sku", 1, "reserved"));
    var ctx = new RequestContext("alice");
    var request = new ReleaseUnitRequest("sku", 1, "reservation-a");

    // act
    service.releaseUnit(ctx, request);

    // assert
    var released = reads.getUnit("alice", "sku", 1);
    assertThat(released.getStatus()).isEqualTo("in_stock");
    assertThat(released.getReservationId()).isNull();
    assertThat(released.getCreatedAt()).isEqualTo(Instant.ofEpochSecond(1700000000));
    assertThat(released.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1700000050));
    assertThat(released.getPhotos())
        .extracting(InventoryUnitItem.PhotoItem::getPhotoId)
        .containsExactly("front");
    assertThat(reads.getSku("alice", "sku").getVersion()).isEqualTo(2);
    assertThat(reads.getSku("alice", "sku").getGsi1pk())
        .isEqualTo(InventorySkuItem.formatGsi1pk("alice", true));
    assertThat(
            auditTable.scan().items().stream().filter(item -> item.getEventType() != null).toList())
        .singleElement()
        .satisfies(
            audit -> {
              assertThat(audit.getEventType()).isEqualTo("release");
              assertThat(audit.getReservationId()).isEqualTo("reservation-a");
              assertThat(audit.getBeforeStatus()).isEqualTo("reserved");
              assertThat(audit.getAfterStatus()).isEqualTo("in_stock");
            });
    assertThat(mutations.getReservation("alice", "reservation-a", "sku", 1).getStatus())
        .isEqualTo("released");

    // act
    service.releaseUnit(ctx, request);
    var reserveService = new ReserveUnitService(reads, mutations);
    reserveService.reserveUnit(ctx, new ReserveUnitRequest("operation-b", "reservation-b", "sku"));
    service.releaseUnit(ctx, request);

    // assert
    assertThat(reads.getUnit("alice", "sku", 1).getStatus()).isEqualTo("reserved");
    assertThat(reads.getUnit("alice", "sku", 1).getReservationId()).isEqualTo("reservation-b");
    assertThat(reads.getSku("alice", "sku").getVersion()).isEqualTo(3);
    assertThat(
            auditTable.scan().items().stream().filter(item -> item.getEventType() != null).toList())
        .hasSize(2);
  }

  @Test
  void releaseUnitShouldAdoptLegacyReservationAndReplayWithoutUnit() throws Exception {
    // arrange
    var mutations =
        new InventoryMutationRepository(
            dynamoDbClient,
            createUnitOperationTable,
            reserveUnitOperationTable,
            reservationTable,
            TABLE_NAME,
            () -> Instant.ofEpochSecond(1700000050),
            () -> "legacy-release-audit");
    var reads = new InventoryReadRepository(skuTable, unitTable);
    var service = new ReleaseUnitService(reads, mutations);
    skuTable.putItem(sku("alice", "sku", "Card", null));
    var legacyUnit = unit("alice", "sku", 3, "reserved");
    legacyUnit.setReservationId("legacy-order");
    unitTable.putItem(legacyUnit);
    var ctx = new RequestContext("alice");
    var request = new ReleaseUnitRequest("sku", 3, "legacy-order");

    // act
    service.releaseUnit(ctx, request);

    // assert
    assertThat(mutations.getReservation("alice", "legacy-order", "sku", 3).getStatus())
        .isEqualTo("released");

    // arrange
    unitTable.deleteItem(
        Key.builder().partitionValue(legacyUnit.getPk()).sortValue(legacyUnit.getSk()).build());

    // act
    service.releaseUnit(ctx, request);

    // assert
    assertThat(
            auditTable.scan().items().stream().filter(item -> item.getEventType() != null).toList())
        .singleElement()
        .satisfies(audit -> assertThat(audit.getEventType()).isEqualTo("release"));
  }

  @Test
  void releaseUnitShouldRejectInvalidOwnershipAndTerminalSale() {
    // arrange
    var mutations =
        new InventoryMutationRepository(
            dynamoDbClient,
            createUnitOperationTable,
            reserveUnitOperationTable,
            reservationTable,
            TABLE_NAME,
            () -> Instant.EPOCH,
            () -> "audit");
    var reads = new InventoryReadRepository(skuTable, unitTable);
    var service = new ReleaseUnitService(reads, mutations);
    var ctx = new RequestContext("alice");
    var reservedForOther = unit("alice", "sku", 1, "reserved");
    reservedForOther.setReservationId("reservation-b");
    unitTable.putItem(reservedForOther);
    reservationTable.putItem(
        InventoryReservationItem.create("alice", "reservation-a", "sku", 1, "reserved"));
    reservationTable.putItem(
        InventoryReservationItem.create("alice", "reservation-sold", "sku", 2, "sold"));
    unitTable.putItem(unit("alice", "sku", 4, "in_stock"));
    reservationTable.putItem(
        InventoryReservationItem.create("alice", "reservation-in-stock", "sku", 4, "reserved"));

    // act / assert
    assertThatThrownBy(
            () -> service.releaseUnit(ctx, new ReleaseUnitRequest("sku", 1, "reservation-a")))
        .isInstanceOf(ConflictException.class)
        .hasMessage("unit is not reserved for this reservation");
    assertThatThrownBy(
            () -> service.releaseUnit(ctx, new ReleaseUnitRequest("sku", 2, "reservation-sold")))
        .isInstanceOf(ConflictException.class)
        .hasMessage("reservation has already been sold");
    assertThatThrownBy(
            () ->
                service.releaseUnit(ctx, new ReleaseUnitRequest("sku", 4, "reservation-in-stock")))
        .isInstanceOf(ConflictException.class)
        .hasMessage("unit is not reserved for this reservation");
    assertThatThrownBy(() -> service.releaseUnit(ctx, new ReleaseUnitRequest("sku", 3, "missing")))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                service.releaseUnit(
                    new RequestContext("bob"), new ReleaseUnitRequest("sku", 1, "reservation-a")))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void releaseUnitShouldRollBackWhenSkuUpdateCannotCommit() {
    // arrange
    var mutations =
        new InventoryMutationRepository(
            dynamoDbClient,
            createUnitOperationTable,
            reserveUnitOperationTable,
            reservationTable,
            TABLE_NAME,
            () -> Instant.EPOCH,
            () -> "rollback-audit");
    var reads = new InventoryReadRepository(skuTable, unitTable);
    var service = new ReleaseUnitService(reads, mutations);
    var reserved = unit("alice", "missing-sku", 1, "reserved");
    reserved.setReservationId("reservation");
    unitTable.putItem(reserved);

    // act / assert
    assertThatThrownBy(
            () ->
                service.releaseUnit(
                    new RequestContext("alice"),
                    new ReleaseUnitRequest("missing-sku", 1, "reservation")))
        .isInstanceOf(TransactionCanceledException.class);
    assertThat(reads.getUnit("alice", "missing-sku", 1).getStatus()).isEqualTo("reserved");
    assertThat(mutations.getReservation("alice", "reservation", "missing-sku", 1)).isNull();
    assertThat(
            auditTable.scan().items().stream().filter(item -> item.getEventType() != null).toList())
        .isEmpty();
  }

  @Test
  void releaseUnitShouldValidateAndSerializeEmptyResponse() throws Exception {
    // arrange
    var reads = mock(InventoryReadRepository.class);
    var mutations = mock(InventoryMutationRepository.class);
    var service = new ReleaseUnitService(reads, mutations);
    var ctx = new RequestContext("alice");

    // act / assert
    assertThatThrownBy(() -> service.releaseUnit(ctx, new ReleaseUnitRequest(" ", 1, "r")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("sku_id is required");
    assertThatThrownBy(() -> service.releaseUnit(ctx, new ReleaseUnitRequest("sku", -1, "r")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("sequence_number must be nonnegative");
    assertThatThrownBy(() -> service.releaseUnit(ctx, new ReleaseUnitRequest("sku", 1, " ")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("reservation_id is required");
    verifyNoInteractions(reads, mutations);
    assertThat(new ObjectMapper().writeValueAsString(new ReleaseUnitResponse())).isEqualTo("{}");
    assertThat(
            new ObjectMapper()
                .readValue(
                    "{\"sku_id\":\"sku\",\"sequence_number\":1,\"reservation_id\":\"r\"}",
                    ReleaseUnitRequest.class))
        .isEqualTo(new ReleaseUnitRequest("sku", 1, "r"));
  }

  @Test
  void releaseUnitShouldPropagateLifecycleReadFailuresBeforeReadingUnit() {
    // arrange
    var reads = mock(InventoryReadRepository.class);
    var mutations = mock(InventoryMutationRepository.class);
    var failure = new IllegalStateException("storage failed");
    when(mutations.getReservation("alice", "reservation", "sku", 1)).thenThrow(failure);
    var service = new ReleaseUnitService(reads, mutations);

    // act / assert
    assertThatThrownBy(
            () ->
                service.releaseUnit(
                    new RequestContext("alice"), new ReleaseUnitRequest("sku", 1, "reservation")))
        .isSameAs(failure);
    verifyNoInteractions(reads);
  }

  @Test
  void sellUnitShouldRetainOwnershipAndReplayAfterTheUnitIsDeleted() throws Exception {
    // arrange
    var mutations =
        new InventoryMutationRepository(
            dynamoDbClient,
            createUnitOperationTable,
            reserveUnitOperationTable,
            reservationTable,
            TABLE_NAME,
            () -> Instant.ofEpochSecond(1700000060),
            () -> "sell-audit-" + ++nextAuditId);
    var reads = new InventoryReadRepository(skuTable, unitTable);
    var service = new SellUnitService(reads, mutations);
    skuTable.putItem(sku("alice", "sku", "Card", "1.00"));
    var reserved = unit("alice", "sku", 1, "reserved", List.of(photo("front")));
    reserved.setReservationId("reservation-a");
    reserved.setUpdatedAt(Instant.ofEpochSecond(1700000010));
    unitTable.putItem(reserved);
    dynamoDbClient.updateItem(
        updateRequest ->
            updateRequest
                .tableName(TABLE_NAME)
                .key(
                    Map.of(
                        "pk", AttributeValue.builder().s(reserved.getPk()).build(),
                        "sk", AttributeValue.builder().s(reserved.getSk()).build()))
                .updateExpression("SET custom_attribute = :custom")
                .expressionAttributeValues(
                    Map.of(":custom", AttributeValue.builder().s("preserve").build())));
    reservationTable.putItem(
        InventoryReservationItem.create("alice", "reservation-a", "sku", 1, "reserved"));
    var ctx = new RequestContext("alice");
    var request = new SellUnitRequest("sku", 1, "reservation-a");
    var skuBefore = reads.getSku("alice", "sku");

    // act
    service.sellUnit(ctx, request);
    var sold = reads.getUnit("alice", "sku", 1);
    var skuAfter = reads.getSku("alice", "sku");

    // assert
    assertThat(sold.getStatus()).isEqualTo("sold");
    assertThat(sold.getReservationId()).isEqualTo("reservation-a");
    assertThat(sold.getCreatedAt()).isEqualTo(Instant.ofEpochSecond(1700000000));
    assertThat(sold.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1700000060));
    assertThat(sold.getPhotos())
        .extracting(InventoryUnitItem.PhotoItem::getPhotoId)
        .containsExactly("front");
    assertThat(
            dynamoDbClient
                .getItem(
                    getRequest ->
                        getRequest
                            .tableName(TABLE_NAME)
                            .key(
                                Map.of(
                                    "pk", AttributeValue.builder().s(sold.getPk()).build(),
                                    "sk", AttributeValue.builder().s(sold.getSk()).build()))
                            .consistentRead(true))
                .item()
                .get("custom_attribute")
                .s())
        .isEqualTo("preserve");
    assertThat(skuAfter.getVersion()).isEqualTo(skuBefore.getVersion() + 1);
    assertThat(skuAfter.getGsi1pk()).isEqualTo(skuBefore.getGsi1pk());
    assertThat(skuAfter.getGsi1sk()).isEqualTo(skuBefore.getGsi1sk());
    assertThat(
            auditTable.scan().items().stream().filter(item -> item.getEventType() != null).toList())
        .singleElement()
        .satisfies(
            audit -> {
              assertThat(audit.getEventType()).isEqualTo("sell");
              assertThat(audit.getReservationId()).isEqualTo("reservation-a");
              assertThat(audit.getBeforeStatus()).isEqualTo("reserved");
              assertThat(audit.getAfterStatus()).isEqualTo("sold");
            });
    assertThat(mutations.getReservation("alice", "reservation-a", "sku", 1).getStatus())
        .isEqualTo("sold");

    // arrange
    unitTable.deleteItem(
        Key.builder().partitionValue(sold.getPk()).sortValue(sold.getSk()).build());

    // act
    service.sellUnit(ctx, request);

    // assert
    assertThat(reads.getSku("alice", "sku").getVersion()).isEqualTo(skuAfter.getVersion());
    assertThat(
            auditTable.scan().items().stream().filter(item -> item.getEventType() != null).toList())
        .hasSize(1);
  }

  @Test
  void sellUnitShouldAdoptLegacyReservationAndRejectConflictingStates() throws Exception {
    // arrange
    var mutations =
        new InventoryMutationRepository(
            dynamoDbClient,
            createUnitOperationTable,
            reserveUnitOperationTable,
            reservationTable,
            TABLE_NAME,
            () -> Instant.ofEpochSecond(1700000070),
            () -> "legacy-sell-audit");
    var reads = new InventoryReadRepository(skuTable, unitTable);
    var service = new SellUnitService(reads, mutations);
    skuTable.putItem(sku("alice", "sku", "Card", "1.00"));
    var legacyUnit = unit("alice", "sku", 1, "reserved");
    legacyUnit.setReservationId("legacy-order");
    unitTable.putItem(legacyUnit);
    reservationTable.putItem(
        InventoryReservationItem.create("alice", "released-order", "sku", 2, "released"));
    reservationTable.putItem(
        InventoryReservationItem.create("alice", "reserved-order", "sku", 3, "reserved"));
    var inStock = unit("alice", "sku", 3, "in_stock");
    inStock.setReservationId("reserved-order");
    unitTable.putItem(inStock);
    var reservedForOther = unit("alice", "sku", 4, "reserved");
    reservedForOther.setReservationId("another-reservation");
    unitTable.putItem(reservedForOther);
    var ctx = new RequestContext("alice");

    // act
    service.sellUnit(ctx, new SellUnitRequest("sku", 1, "legacy-order"));

    // assert
    assertThat(mutations.getReservation("alice", "legacy-order", "sku", 1).getStatus())
        .isEqualTo("sold");
    assertThat(reads.getUnit("alice", "sku", 1).getStatus()).isEqualTo("sold");
    assertThatThrownBy(() -> service.sellUnit(ctx, new SellUnitRequest("sku", 2, "released-order")))
        .isInstanceOf(ConflictException.class)
        .hasMessage("reservation has already been released");
    assertThatThrownBy(() -> service.sellUnit(ctx, new SellUnitRequest("sku", 3, "reserved-order")))
        .isInstanceOf(ConflictException.class)
        .hasMessage("unit is not reserved for this reservation");
    assertThatThrownBy(
            () ->
                service.sellUnit(
                    new RequestContext("bob"), new SellUnitRequest("sku", 1, "legacy-order")))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");
    assertThatThrownBy(() -> service.sellUnit(ctx, new SellUnitRequest("sku", 9, "missing")))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Not Found");
    assertThatThrownBy(
            () ->
                service.sellUnit(
                    ctx, new SellUnitRequest("sku", 4, "reservation-for-someone-else")))
        .isInstanceOf(ConflictException.class)
        .hasMessage("unit is not reserved for this reservation");
  }

  @Test
  void sellUnitShouldRollBackWhenSkuIsMissing() throws Exception {
    // arrange
    var mutations =
        new InventoryMutationRepository(
            dynamoDbClient,
            createUnitOperationTable,
            reserveUnitOperationTable,
            reservationTable,
            TABLE_NAME,
            () -> Instant.EPOCH,
            () -> "rollback-sell-audit");
    var reads = new InventoryReadRepository(skuTable, unitTable);
    var service = new SellUnitService(reads, mutations);
    var reserved = unit("alice", "missing-sku", 1, "reserved");
    reserved.setReservationId("reservation");
    unitTable.putItem(reserved);
    var ctx = new RequestContext("alice");
    var request = new SellUnitRequest("missing-sku", 1, "reservation");

    // act / assert
    assertThatThrownBy(() -> service.sellUnit(ctx, request))
        .isInstanceOf(TransactionCanceledException.class);
    assertThat(reads.getUnit("alice", "missing-sku", 1).getStatus()).isEqualTo("reserved");
    assertThat(mutations.getReservation("alice", "reservation", "missing-sku", 1)).isNull();
    assertThat(
            auditTable.scan().items().stream().filter(item -> item.getEventType() != null).toList())
        .isEmpty();
    assertThat(reads.getUnit("alice", "missing-sku", 1).getReservationId())
        .isEqualTo("reservation");
  }

  @Test
  void sellUnitShouldValidateBeforeStorage() {
    // arrange
    var reads = mock(InventoryReadRepository.class);
    var mutations = mock(InventoryMutationRepository.class);
    var service = new SellUnitService(reads, mutations);
    var ctx = new RequestContext("alice");

    // act / assert
    assertThatThrownBy(() -> service.sellUnit(ctx, new SellUnitRequest(" ", 1, "r")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("sku_id is required");
    assertThatThrownBy(() -> service.sellUnit(ctx, new SellUnitRequest("sku", -1, "r")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("sequence_number must be nonnegative");
    assertThatThrownBy(() -> service.sellUnit(ctx, new SellUnitRequest("sku", 1, " ")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("reservation_id is required");
    verifyNoInteractions(reads, mutations);
  }

  @Test
  void sellUnitShouldPropagateLifecycleReadFailureAndSerializeContract() throws Exception {
    // arrange
    var reads = mock(InventoryReadRepository.class);
    var mutations = mock(InventoryMutationRepository.class);
    var failure = new IllegalStateException("storage failed");
    when(mutations.getReservation("alice", "reservation", "sku", 1)).thenThrow(failure);
    var service = new SellUnitService(reads, mutations);

    // act / assert
    assertThatThrownBy(
            () ->
                service.sellUnit(
                    new RequestContext("alice"), new SellUnitRequest("sku", 1, "reservation")))
        .isSameAs(failure);
    verifyNoInteractions(reads);
    assertThat(new ObjectMapper().writeValueAsString(new SellUnitResponse())).isEqualTo("{}");
    assertThat(
            new ObjectMapper()
                .readValue(
                    "{\"sku_id\":\"sku\",\"sequence_number\":1,\"reservation_id\":\"r\"}",
                    SellUnitRequest.class))
        .isEqualTo(new SellUnitRequest("sku", 1, "r"));
  }

  @Test
  void reserveUnitShouldPropagateReceiptStorageFailures() {
    // arrange
    var reads = mock(InventoryReadRepository.class);
    var mutations = mock(InventoryMutationRepository.class);
    var failure = new IllegalStateException("storage failed");
    when(mutations.getReserveUnitOperation("alice", "operation")).thenThrow(failure);
    var service = new ReserveUnitService(reads, mutations);

    // act / assert
    assertThatThrownBy(
            () ->
                service.reserveUnit(
                    new RequestContext("alice"),
                    new ReserveUnitRequest("operation", "reservation", "sku")))
        .isSameAs(failure);
    verifyNoInteractions(reads);
  }

  @Test
  void reserveUnitShouldValidateAndReportMissingOrExhaustedStock() throws Exception {
    // arrange
    var enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    var mutations =
        new InventoryMutationRepository(
            dynamoDbClient,
            createUnitOperationTable,
            enhanced.table(
                TABLE_NAME, TableSchema.fromBean(InventoryReserveUnitOperationItem.class)),
            reservationTable,
            TABLE_NAME,
            () -> Instant.EPOCH,
            () -> "audit");
    var service =
        new ReserveUnitService(new InventoryReadRepository(skuTable, unitTable), mutations);
    var ctx = new RequestContext("alice");

    // act / assert
    assertThatThrownBy(() -> service.reserveUnit(ctx, new ReserveUnitRequest(" ", "r", "s")))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.reserveUnit(ctx, new ReserveUnitRequest("a", " ", "s")))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.reserveUnit(ctx, new ReserveUnitRequest("a", "r", null)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.reserveUnit(ctx, new ReserveUnitRequest("a", "r", "s")))
        .isInstanceOf(NotFoundException.class);
    skuTable.putItem(sku("alice", "s", "Card", null));
    assertThatThrownBy(() -> service.reserveUnit(ctx, new ReserveUnitRequest("a", "r", "s")))
        .isInstanceOf(ConflictException.class)
        .hasMessage("No in-stock units are available for this SKU");
    assertThatThrownBy(
            () ->
                service.reserveUnit(
                    new RequestContext("bob"), new ReserveUnitRequest("a", "r", "s")))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void reserveUnitShouldRollbackFailedConditionsAndAllowExplicitRetry() {
    // arrange
    var enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    var mutations =
        new InventoryMutationRepository(
            dynamoDbClient,
            createUnitOperationTable,
            enhanced.table(
                TABLE_NAME, TableSchema.fromBean(InventoryReserveUnitOperationItem.class)),
            reservationTable,
            TABLE_NAME,
            () -> Instant.EPOCH,
            () -> "audit");
    skuTable.putItem(sku("alice", "sku", "Card", null));
    unitTable.putItem(unit("alice", "sku", 1, "sold"));

    // act / assert
    assertThatThrownBy(() -> mutations.reserveUnit("alice", "a", "r", "sku", 1))
        .isInstanceOf(TransactionCanceledException.class);
    assertThat(mutations.getReserveUnitOperation("alice", "a")).isNull();
    assertThat(new InventoryReadRepository(skuTable, unitTable).getSku("alice", "sku").getVersion())
        .isEqualTo(1);
    assertThat(
            auditTable.scan().items().stream().filter(item -> item.getEventType() != null).toList())
        .isEmpty();
    assertThat(
            enhanced
                .table(TABLE_NAME, TableSchema.fromBean(InventoryReservationItem.class))
                .scan()
                .items()
                .stream()
                .filter(item -> item.getSk().startsWith("RESERVATION#"))
                .toList())
        .isEmpty();

    // arrange
    unitTable.putItem(unit("alice", "sku", 1, "in_stock"));

    // act / assert
    assertThat(mutations.reserveUnit("alice", "a", "r", "sku", 1).getSequenceNumber()).isEqualTo(1);
  }

  @Test
  void createUnitShouldPreserveSkuAndReplayOriginalResultAfterRemoval() throws Exception {
    // arrange
    var reads = new InventoryReadRepository(skuTable, unitTable);
    var mutations =
        new InventoryMutationRepository(
            dynamoDbClient,
            createUnitOperationTable,
            reserveUnitOperationTable,
            reservationTable,
            TABLE_NAME,
            () -> Instant.ofEpochSecond(1700000000),
            () -> "audit-" + ++nextAuditId);
    var service = new CreateUnitService(reads, mutations, new FakeGamesService());
    var ctx = new RequestContext("creator");
    var request =
        new CreateUnitRequest(
            "import:1#row:0",
            "mtg",
            "card",
            "normal",
            Condition.NM,
            "Original",
            "SET",
            "Set",
            "1",
            new BigDecimal("2.5"),
            "fetch-card",
            42,
            List.of("photo-b", "photo-a"));
    // act
    var created = service.createUnit(ctx, request);
    mutations.deleteUnit(ctx.user(), created.skuId(), created.sequenceNumber(), null);
    var replay =
        service.createUnit(
            ctx,
            new CreateUnitRequest(
                request.operationId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null));
    var second =
        service.createUnit(
            ctx,
            new CreateUnitRequest(
                "second",
                "mtg",
                "card",
                "normal",
                Condition.NM,
                "Replacement",
                "OTHER",
                "Other",
                "9",
                new BigDecimal("9.00"),
                "other-card",
                99,
                List.of()));
    // assert
    assertThat(created.sequenceNumber()).isZero();
    assertThat(created.location()).isEqualTo("A0-0");
    assertThat(replay).isEqualTo(created);
    assertThat(second.sequenceNumber()).isEqualTo(1);
    var sku = reads.getSku(ctx.user(), created.skuId());
    assertThat(sku.getName()).isEqualTo("Original");
    assertThat(sku.getSuggestedPrice()).isEqualTo("2.50");
    assertThat(sku.getFetchtcgSetId()).isEqualTo(42);
    assertThat(sku.getVersion()).isEqualTo(3);
    var unit = reads.getUnit(ctx.user(), created.skuId(), 0);
    assertThat(unit.getStatus()).isEqualTo("removed");
    assertThat(unit.getPhotos())
        .extracting(InventoryUnitItem.PhotoItem::getPhotoId)
        .containsExactly("photo-b", "photo-a");
    assertThat(unit.getCreatedAt()).isEqualTo(Instant.ofEpochSecond(1700000000));
    assertThat(
            auditTable.scan().items().stream()
                .filter(a -> "create".equals(a.getEventType()))
                .count())
        .isEqualTo(2);
    var moved =
        new UpdateUnitService(reads, mutations, new FakeGamesService())
            .updateUnit(
                ctx, new UpdateUnitRequest(second.skuId(), second.sequenceNumber(), Condition.LP));
    assertThat(
            service.createUnit(
                ctx,
                new CreateUnitRequest(
                    "second", null, null, null, null, null, null, null, null, null, null, 0, null)))
        .isEqualTo(second);
    assertThat(reads.getUnit(ctx.user(), second.skuId(), second.sequenceNumber())).isNull();
    assertThat(reads.getUnit(ctx.user(), moved.skuId(), second.sequenceNumber())).isNotNull();
    assertThat(service.createUnit(new RequestContext("other"), request).sequenceNumber()).isZero();
  }

  @Test
  void createUnitShouldValidateBeforeAllocatingAndLeaveNoPartialWrites() throws Exception {
    // arrange
    var reads = new InventoryReadRepository(skuTable, unitTable);
    var mutations =
        new InventoryMutationRepository(
            dynamoDbClient,
            createUnitOperationTable,
            reserveUnitOperationTable,
            reservationTable,
            TABLE_NAME,
            () -> Instant.ofEpochSecond(1700000000),
            () -> "creation-audit");
    var service = new CreateUnitService(reads, mutations, new FakeGamesService());
    var ctx = new RequestContext("validation");
    // act and assert
    assertThatThrownBy(
            () ->
                service.createUnit(
                    ctx,
                    new CreateUnitRequest(
                        "id",
                        "mtg",
                        "card",
                        "normal",
                        Condition.NM,
                        "Card",
                        "SET",
                        "Set",
                        "1",
                        new BigDecimal("1.001"),
                        null,
                        1,
                        List.of())))
        .isInstanceOf(BadRequestException.class);
    assertThat(dynamoDbClient.scan(r -> r.tableName(TABLE_NAME)).count()).isZero();
    var request =
        new CreateUnitRequest(
            "id",
            "mtg",
            "card",
            "normal",
            Condition.NM,
            "Card",
            "SET",
            "Set",
            "1",
            null,
            null,
            1,
            List.of());
    var json = new ObjectMapper();
    assertThat(json.readValue(json.writeValueAsString(request), CreateUnitRequest.class))
        .isEqualTo(request);
    var created = service.createUnit(ctx, request);
    assertThat(created.sequenceNumber()).isZero();
    assertThat(json.readTree(json.writeValueAsString(created)).fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder("sku_id", "sequence_number", "location");
    assertThatThrownBy(
            () ->
                mutations.createUnit(
                    ctx.user(),
                    request.operationId(),
                    reads.getSku(ctx.user(), created.skuId()),
                    request.photos(),
                    false))
        .isInstanceOf(TransactionCanceledException.class);
    assertThat(reads.getSku(ctx.user(), created.skuId()).getVersion()).isEqualTo(1);
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
    reserved.setReservationId("order-1");
    unitTable.putItem(reserved);
    var sold = unit("alice", "sku", 3, "sold");
    sold.setReservationId("order-2");
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
