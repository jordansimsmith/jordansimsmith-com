package com.jordansimsmith.tcginventory.inventory.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Testcontainers
class InventoryReadServiceIntegrationTest {
  private static final String TABLE_NAME = "inventory_units_test";

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<InventoryUnitItem> unitTable;
  private static DynamoDbTable<InventorySkuItem> skuTable;

  private FindUnitsService service;
  private FindSkusService findSkusService;
  private GetSkuService getSkuService;

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
  }

  @Test
  void findSkusShouldFilterSortAndPaginateByAuthenticatedUser() throws Exception {
    // arrange
    skuTable.putItem(sku("alice", "alpha", "Alpha Card", "1.25"));
    skuTable.putItem(sku("alice", "beta", "Beta Card", null));
    skuTable.putItem(sku("alice", "else", "Other Card", "2"));
    skuTable.putItem(sku("bob", "alpha", "Alpha Card", "9"));

    // act
    var firstPage =
        findSkusService.findSkus(
            new RequestContext("alice"), new FindSkusRequest("mtg", null, 1, null));
    var nextPage =
        findSkusService.findSkus(
            new RequestContext("alice"),
            new FindSkusRequest("mtg", null, 1, firstPage.nextContinuation()));
    var finalPage =
        findSkusService.findSkus(
            new RequestContext("alice"),
            new FindSkusRequest("mtg", null, 1, nextPage.nextContinuation()));
    var emptyPage =
        findSkusService.findSkus(
            new RequestContext("alice"),
            new FindSkusRequest("mtg", null, 1, finalPage.nextContinuation()));
    var bobPage =
        findSkusService.findSkus(
            new RequestContext("bob"), new FindSkusRequest("mtg", null, 20, null));

    // assert
    assertThat(firstPage.skus()).hasSize(1);
    assertThat(firstPage.skus().get(0).name()).isEqualTo("Alpha Card");
    assertThat(firstPage.skus().get(0).externalId()).isEqualTo("alpha");
    assertThat(firstPage.skus().get(0).lastPublishedPrice()).isEqualByComparingTo("1.25");
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
            new RequestContext("alice"), new FindSkusRequest("mtg", "aLp", null, null));

    // assert
    assertThat(response.skus())
        .extracting(sku -> sku.name())
        .containsExactly("Alpha Card", "Alpine Card");
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
                    new RequestContext("alice"), new FindSkusRequest(" ", null, null, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("game is required");
    assertThatThrownBy(
            () ->
                findService.findSkus(
                    new RequestContext("alice"), new FindSkusRequest("mtg", null, 101, null)))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("limit must be between 1 and 100");
    assertThatThrownBy(
            () ->
                findService.findSkus(
                    new RequestContext("alice"), new FindSkusRequest("mtg", null, null, "%")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("Invalid continuation");
    assertThatThrownBy(() -> getService.getSku(new RequestContext("alice"), new GetSkuRequest(" ")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("sku_id is required");
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
    assertThat(zero.lastPublishedPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(missing.lastPublishedPrice()).isNull();
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
    unitTable.putItem(reserved);
    unitTable.putItem(unit("alice", "sku", 3, "sold"));
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
    assertThat(secondPage.nextContinuation()).isNotNull();
    assertThat(allAliceUnits.units())
        .extracting("status")
        .containsExactly(UnitStatus.RESERVED, UnitStatus.SOLD);
    assertThat(bobPage.units()).extracting("sequenceNumber").containsExactly(2);
    assertThat(bobPage.units().getFirst().photos()).isEmpty();
    assertThat(bobPage.units().getFirst().createdAt()).isEqualTo(1700000000L);
    assertThat(bobPage.units().getFirst().updatedAt()).isNull();
    var objectMapper = new ObjectMapper();
    assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(secondPage)))
        .isEqualTo(
            objectMapper.readTree(
                """
                {"units":[{"sequence_number":2,"location":"A0-2","status":"reserved","photos":["front","back"],"created_at":1700000000,"updated_at":1700000001}],"next_continuation":"%s"}
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

  private static InventorySkuItem sku(
      String user, String skuId, String name, String lastPublishedPrice) {
    var item = new InventorySkuItem();
    item.setPk(InventorySkuItem.formatPk(user, skuId));
    item.setSk("SKU");
    item.setSkuId(skuId);
    item.setGame("mtg");
    item.setExternalId(skuId);
    item.setName(name);
    item.setSetCode("set");
    item.setSetName("Set");
    item.setCollectorNumber("1");
    item.setFinish("normal");
    item.setCondition("NM");
    item.setLastPublishedPrice(lastPublishedPrice);
    item.setGsi2pk(InventorySkuItem.formatGsi2pk(user));
    item.setGsi2sk(InventorySkuItem.formatGsi2skPrefix("mtg", name.toLowerCase()) + "#" + skuId);
    return item;
  }
}
