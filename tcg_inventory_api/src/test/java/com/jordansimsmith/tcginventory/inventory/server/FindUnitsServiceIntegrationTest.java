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
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
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
class FindUnitsServiceIntegrationTest {
  private static final String TABLE_NAME = "inventory_units_test";

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbTable<InventoryUnitItem> unitTable;

  private FindUnitsService service;

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
    unitTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(InventoryUnitItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, unitTable);
  }

  @AfterAll
  static void tearDownAfterClass() {
    dynamoDbClient.close();
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    service = new FindUnitsService(new InventoryReadRepository(unitTable), new ObjectMapper());
  }

  @Test
  void findUnitsShouldPageInSequenceAndReturnOrderedPhotoIds() throws Exception {
    // arrange
    unitTable.putItem(unit("alice", "sku", 1, "removed"));
    unitTable.putItem(unit("alice", "sku", 2, "reserved", List.of(photo("front"), photo("back"))));
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
    assertThat(secondPage.units().getFirst().status()).isEqualTo("reserved");
    assertThat(secondPage.units().getFirst().photos()).containsExactly("front", "back");
    assertThat(secondPage.nextContinuation()).isNotNull();
    assertThat(allAliceUnits.units()).extracting("status").containsExactly("reserved", "sold");
    assertThat(bobPage.units()).extracting("sequenceNumber").containsExactly(2);
    assertThat(bobPage.units().getFirst().photos()).isEmpty();
    var objectMapper = new ObjectMapper();
    assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(secondPage)))
        .isEqualTo(
            objectMapper.readTree(
                """
                {"units":[{"sequence_number":2,"location":"A0-2","status":"reserved","photos":["front","back"]}],"next_continuation":"%s"}
                """
                    .formatted(secondPage.nextContinuation())));
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
    item.setPhotos(photos);
    return item;
  }

  private static InventoryUnitItem.PhotoItem photo(String photoId) {
    var item = new InventoryUnitItem.PhotoItem();
    item.setPhotoId(photoId);
    return item;
  }
}
