package com.jordansimsmith.tcginventory.listings.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.fetchtcg.client.CreateListingImageResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FakeInventoryService;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsResponse;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuResponse;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.inventory.client.UpdateSkuRequest;
import com.jordansimsmith.tcginventory.listings.client.FindListingsRequest;
import com.jordansimsmith.tcginventory.listings.client.FindListingsResponse;
import com.jordansimsmith.tcginventory.listings.client.Listing;
import com.jordansimsmith.tcginventory.listings.client.UpdateListingRequest;
import com.jordansimsmith.tcginventory.listings.client.UpdateListingResponse;
import com.jordansimsmith.tcginventory.photos.client.FakePhotosService;
import com.jordansimsmith.tcginventory.photos.client.GetPhotoBytesRequest;
import com.jordansimsmith.tcginventory.photos.client.GetPhotoBytesResponse;
import com.jordansimsmith.tcginventory.photos.client.PhotosService;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

@Testcontainers
class ListingServiceIntegrationTest {
  private static final String TABLE_NAME = "listings_test";
  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private static DynamoDbClient dynamoDbClient;
  private static DynamoDbEnhancedClient dynamoDbEnhancedClient;
  private static DynamoDbTable<CurrentListingItem> currentListingTable;
  private static DynamoDbTable<ListingAssociationItem> listingAssociationTable;
  private ListingRepository listingRepository;
  private FindListingsService findListingsService;

  @BeforeAll
  static void setUpBeforeClass() {
    dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(dynamoDbContainer.getEndpoint())
            .region(Region.AP_SOUTHEAST_2)
            .build();
    dynamoDbEnhancedClient =
        DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    currentListingTable =
        dynamoDbEnhancedClient.table(TABLE_NAME, TableSchema.fromBean(CurrentListingItem.class));
    listingAssociationTable =
        dynamoDbEnhancedClient.table(
            TABLE_NAME, TableSchema.fromBean(ListingAssociationItem.class));
    DynamoDbUtils.createTable(dynamoDbClient, currentListingTable);
  }

  @BeforeEach
  void setUp() {
    DynamoDbUtils.reset(dynamoDbClient);
    listingRepository =
        new ListingRepository(
            currentListingTable,
            listingAssociationTable,
            dynamoDbEnhancedClient,
            dynamoDbClient,
            TABLE_NAME);
    findListingsService = new FindListingsService(listingRepository);
  }

  @Test
  void saveShouldWriteBothRecordsWithExactKeysAndNumericListingId() {
    // act
    listingRepository.saveListing("alice", "mtg#scryfall#opaque#normal#NM", 42);
    listingRepository.saveListing("alice", "mtg#scryfall#opaque#normal#NM", 42);

    // assert
    var current =
        dynamoDbClient
            .getItem(
                GetItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(
                        Map.of(
                            CurrentListingItem.PK,
                            AttributeValue.fromS("USER#alice#LISTINGS"),
                            CurrentListingItem.SK,
                            AttributeValue.fromS("SKU#mtg#scryfall#opaque#normal#NM")))
                    .consistentRead(true)
                    .build())
            .item();
    var association =
        dynamoDbClient
            .getItem(
                GetItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(
                        Map.of(
                            ListingAssociationItem.PK,
                            AttributeValue.fromS("USER#alice#LISTINGS"),
                            ListingAssociationItem.SK,
                            AttributeValue.fromS("LISTING#42")))
                    .consistentRead(true)
                    .build())
            .item();

    assertThat(current)
        .containsEntry(CurrentListingItem.USER, AttributeValue.fromS("alice"))
        .containsEntry(
            CurrentListingItem.SKU_ID, AttributeValue.fromS("mtg#scryfall#opaque#normal#NM"))
        .containsEntry(CurrentListingItem.LISTING_ID, AttributeValue.fromN("42"));
    assertThat(association)
        .containsEntry(ListingAssociationItem.USER, AttributeValue.fromS("alice"))
        .containsEntry(
            ListingAssociationItem.SKU_ID, AttributeValue.fromS("mtg#scryfall#opaque#normal#NM"))
        .containsEntry(ListingAssociationItem.LISTING_ID, AttributeValue.fromN("42"));
  }

  @Test
  void findListingsShouldPreserveRequestOrderDeduplicateAndOmitUnknownIds() throws Exception {
    // arrange
    listingRepository.saveListing("alice", "first-sku", 42);
    listingRepository.saveListing("alice", "second-sku", 43);
    var ctx = new RequestContext("alice");

    // act
    var response =
        findListingsService.findListings(ctx, new FindListingsRequest(List.of(43, 999, 42, 43)));

    // assert
    assertThat(response.listings())
        .containsExactly(new Listing(43, "second-sku"), new Listing(42, "first-sku"));
  }

  @Test
  void findListingsShouldReturnHistoricalAssociationsAfterReplacementAndDeletion()
      throws Exception {
    // arrange
    listingRepository.saveListing("alice", "sku", 42);
    listingRepository.saveListing("alice", "sku", 43);
    assertThat(listingRepository.getCurrentListing("alice", "sku").getListingId()).isEqualTo(43);
    listingRepository.deleteCurrentListing("alice", "sku");

    // act
    var response =
        findListingsService.findListings(
            new RequestContext("alice"), new FindListingsRequest(List.of(42, 43)));

    // assert
    assertThat(listingRepository.getCurrentListing("alice", "sku")).isNull();
    assertThat(response.listings()).containsExactly(new Listing(42, "sku"), new Listing(43, "sku"));
  }

  @Test
  void findListingsShouldScopeAssociationsToUser() throws Exception {
    // arrange
    listingRepository.saveListing("alice", "alice-sku", 42);
    listingRepository.saveListing("bob", "bob-sku", 43);

    // act
    var response =
        findListingsService.findListings(
            new RequestContext("alice"), new FindListingsRequest(List.of(42, 43)));

    // assert
    assertThat(response.listings()).containsExactly(new Listing(42, "alice-sku"));
  }

  @Test
  void findListingsShouldReturnEmptyWithoutStorageForEmptyInput() throws Exception {
    // arrange
    var repository = mock(ListingRepository.class);
    var service = new FindListingsService(repository);

    // act
    var response =
        service.findListings(new RequestContext("alice"), new FindListingsRequest(List.of()));

    // assert
    assertThat(response.listings()).isEmpty();
    verifyNoInteractions(repository);
  }

  @Test
  void findListingsShouldRejectNullOversizedAndNullContainingLists() {
    // act / assert
    assertThatThrownBy(
            () ->
                findListingsService.findListings(
                    new RequestContext("alice"), new FindListingsRequest(null)))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                findListingsService.findListings(
                    new RequestContext("alice"),
                    new FindListingsRequest(Collections.nCopies(101, 1))))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                findListingsService.findListings(
                    new RequestContext("alice"), new FindListingsRequest(Arrays.asList(1, null))))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void requestAndResponseShouldUseSnakeCaseJson() throws Exception {
    // arrange
    var objectMapper = new ObjectMapper();
    var request = new FindListingsRequest(List.of(4, 5));
    var response = new FindListingsResponse(List.of(new Listing(4, "sku")));

    // act
    var requestJson = objectMapper.writeValueAsString(request);
    var responseJson = objectMapper.writeValueAsString(response);

    // assert
    assertThat(requestJson).isEqualTo("{\"listing_ids\":[4,5]}");
    assertThat(responseJson).isEqualTo("{\"listings\":[{\"listing_id\":4,\"sku_id\":\"sku\"}]}");
    assertThat(objectMapper.readValue(requestJson, FindListingsRequest.class)).isEqualTo(request);
  }

  @Test
  void saveListingShouldPropagateTransactionFailures() {
    // arrange
    var dynamoDbClient = mock(DynamoDbClient.class);
    var failure = new IllegalStateException("storage failed");
    when(dynamoDbClient.transactWriteItems(any(TransactWriteItemsRequest.class)))
        .thenThrow(failure);
    var repository =
        new ListingRepository(
            currentListingTable,
            listingAssociationTable,
            dynamoDbEnhancedClient,
            dynamoDbClient,
            TABLE_NAME);

    // act / assert
    assertThatThrownBy(() -> repository.saveListing("alice", "sku", 42)).isSameAs(failure);
  }

  @Test
  void updateListingShouldSynchronizeAndRetainHistoryAfterDelisting() throws Exception {
    // arrange
    var inventory = new FakeInventoryService();
    var photos = new FakePhotosService();
    var provider = new FakeFetchTcgService();
    var ctx = new RequestContext("alice");
    var sku = listingSku();
    inventory.seedSku("alice", sku, true);
    inventory.seedUnits(
        "alice",
        "sku",
        List.of(new Unit(1, "A1", UnitStatus.IN_STOCK, List.of("front"), 1L, null, null)));
    photos.seedPhoto("alice", "front", new byte[] {1, 2});
    var service = new UpdateListingService(listingRepository, inventory, photos, provider);

    // act
    service.updateListing(ctx, new UpdateListingRequest("sku"));
    var id = listingRepository.getCurrentListing("alice", "sku").getListingId();
    inventory.seedSku("alice", sku, true);
    service.updateListing(ctx, new UpdateListingRequest("sku"));

    // assert
    assertThat(listingRepository.getCurrentListing("alice", "sku").getListingId()).isEqualTo(id);
    assertThat(listingRepository.getCurrentListing("bob", "sku")).isNull();

    // act
    inventory.seedUnits("alice", "sku", List.of());
    inventory.seedSku("alice", sku, true);
    service.updateListing(ctx, new UpdateListingRequest("sku"));
    inventory.seedSku("alice", sku, true);
    service.updateListing(ctx, new UpdateListingRequest("sku"));

    // assert
    assertThat(listingRepository.getCurrentListing("alice", "sku")).isNull();
    assertThat(
            findListingsService.findListings(ctx, new FindListingsRequest(List.of(id))).listings())
        .containsExactly(new Listing(id, "sku"));
  }

  @Test
  void updateListingShouldCollectAllPagesAndProjectFirstInStockPhotos() throws Exception {
    // arrange
    var inventory = mock(InventoryService.class);
    var photos = mock(PhotosService.class);
    var provider = mock(FetchTcgService.class);
    var ctx = new RequestContext("alice");
    when(inventory.getSku(ctx, new GetSkuRequest("sku")))
        .thenReturn(new GetSkuResponse(listingSku()));
    when(inventory.findUnits(ctx, new FindUnitsRequest("sku", 100, null)))
        .thenReturn(new FindUnitsResponse(List.of(), "next"));
    when(inventory.findUnits(ctx, new FindUnitsRequest("sku", 100, "next")))
        .thenReturn(
            new FindUnitsResponse(
                List.of(
                    new Unit(1, "A1", UnitStatus.RESERVED, List.of(), 1L, null, null),
                    new Unit(
                        2, "A2", UnitStatus.IN_STOCK, List.of("front", "back"), 1L, null, null),
                    new Unit(3, "A3", UnitStatus.IN_STOCK, List.of(), 1L, null, null)),
                null));
    when(photos.getPhotoBytes(ctx, new GetPhotoBytesRequest("front")))
        .thenReturn(new GetPhotoBytesResponse(new byte[] {1}));
    when(photos.getPhotoBytes(ctx, new GetPhotoBytesRequest("back")))
        .thenReturn(new GetPhotoBytesResponse(new byte[] {2}));
    when(provider.createListingImage(any(), any()))
        .thenReturn(
            new CreateListingImageResponse("front-url"),
            new CreateListingImageResponse("back-url"));
    when(provider.updateListing(any(), any()))
        .thenReturn(
            new com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingResponse(42, 2));

    // act
    new UpdateListingService(listingRepository, inventory, photos, provider)
        .updateListing(ctx, new UpdateListingRequest("sku"));

    // assert
    var order = inOrder(inventory, photos, provider);
    order.verify(inventory).getSku(ctx, new GetSkuRequest("sku"));
    order.verify(inventory).findUnits(ctx, new FindUnitsRequest("sku", 100, null));
    order.verify(inventory).findUnits(ctx, new FindUnitsRequest("sku", 100, "next"));
    order.verify(photos).getPhotoBytes(ctx, new GetPhotoBytesRequest("front"));
    order
        .verify(provider)
        .createListingImage(
            eq(ctx),
            argThat(
                r ->
                    r.filename().equals("front.jpg")
                        && Arrays.equals(r.imageBytes(), new byte[] {1})));
    order.verify(photos).getPhotoBytes(ctx, new GetPhotoBytesRequest("back"));
    order
        .verify(provider)
        .createListingImage(
            eq(ctx),
            argThat(
                r ->
                    r.filename().equals("back.jpg")
                        && Arrays.equals(r.imageBytes(), new byte[] {2})));
    order
        .verify(provider)
        .updateListing(
            ctx,
            new com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingRequest(
                "card", "raw-nm", 2, new BigDecimal("2.00"), "front-url", List.of("back-url")));
    order.verify(inventory).updateSku(ctx, new UpdateSkuRequest("sku", null, false, 7));
    assertThat(listingRepository.getCurrentListing("alice", "sku").getListingId()).isEqualTo(42);
  }

  @Test
  void updateListingShouldNotApplyEffectsWhenLaterUnitPageFails() throws Exception {
    // arrange
    var inventory = mock(InventoryService.class);
    var photos = mock(PhotosService.class);
    var provider = mock(FetchTcgService.class);
    var failure = new ServiceUnavailableException("unavailable");
    when(inventory.getSku(any(), any())).thenReturn(new GetSkuResponse(listingSku()));
    when(inventory.findUnits(any(), any()))
        .thenReturn(new FindUnitsResponse(List.of(), "next"))
        .thenThrow(failure);

    // act / assert
    assertThatThrownBy(
            () ->
                new UpdateListingService(listingRepository, inventory, photos, provider)
                    .updateListing(new RequestContext("alice"), new UpdateListingRequest("sku")))
        .isSameAs(failure);
    verifyNoInteractions(photos, provider);
    verify(inventory, never()).updateSku(any(), any());
    assertThat(listingRepository.getCurrentListing("alice", "sku")).isNull();
  }

  @Test
  void updateListingShouldRetainAssociationWhenAcknowledgementFails() throws Exception {
    // arrange
    var inventory = mock(InventoryService.class);
    var failure = new IllegalStateException("version changed");
    when(inventory.getSku(any(), any())).thenReturn(new GetSkuResponse(listingSku()));
    when(inventory.findUnits(any(), any()))
        .thenReturn(
            new FindUnitsResponse(
                List.of(new Unit(1, "A1", UnitStatus.IN_STOCK, List.of(), 1L, null, null)), null));
    when(inventory.updateSku(any(), any())).thenThrow(failure);

    // act / assert
    assertThatThrownBy(
            () ->
                new UpdateListingService(
                        listingRepository,
                        inventory,
                        new FakePhotosService(),
                        new FakeFetchTcgService())
                    .updateListing(new RequestContext("alice"), new UpdateListingRequest("sku")))
        .isSameAs(failure);
    assertThat(listingRepository.getCurrentListing("alice", "sku")).isNotNull();
  }

  @Test
  void updateListingShouldPropagateProviderAndPersistenceFailuresBeforeAcknowledgement()
      throws Exception {
    // arrange
    var inventory = mock(InventoryService.class);
    var provider = mock(FetchTcgService.class);
    var ctx = new RequestContext("alice");
    when(inventory.getSku(any(), any())).thenReturn(new GetSkuResponse(listingSku()));
    when(inventory.findUnits(any(), any()))
        .thenReturn(
            new FindUnitsResponse(
                List.of(new Unit(1, "A1", UnitStatus.IN_STOCK, List.of(), 1L, null, null)), null));
    var failure = new ServiceUnavailableException("unavailable");
    when(provider.updateListing(any(), any())).thenThrow(failure);

    // act / assert
    assertThatThrownBy(
            () ->
                new UpdateListingService(
                        listingRepository, inventory, new FakePhotosService(), provider)
                    .updateListing(ctx, new UpdateListingRequest("sku")))
        .isSameAs(failure);
    assertThat(listingRepository.getCurrentListing("alice", "sku")).isNull();
    verify(inventory, never()).updateSku(any(), any());

    // arrange
    var repository = mock(ListingRepository.class);
    var storageFailure = new IllegalStateException("storage failed");
    doThrow(storageFailure).when(repository).saveListing(any(), any(), anyInt());

    // act / assert
    assertThatThrownBy(
            () ->
                new UpdateListingService(
                        repository, inventory, new FakePhotosService(), new FakeFetchTcgService())
                    .updateListing(ctx, new UpdateListingRequest("sku")))
        .isSameAs(storageFailure);
    verify(inventory, never()).updateSku(any(), any());
  }

  @Test
  void updateListingShouldPropagateMissingSkuAndRejectIncompletePublicationData() throws Exception {
    // arrange
    var inventory = new FakeInventoryService();
    var service =
        new UpdateListingService(
            listingRepository, inventory, new FakePhotosService(), new FakeFetchTcgService());
    var ctx = new RequestContext("alice");

    // act / assert
    assertThatThrownBy(() -> service.updateListing(ctx, new UpdateListingRequest("sku")))
        .isInstanceOf(NotFoundException.class);
    for (var missingPrice : List.of(true, false)) {
      var sku = listingSku();
      inventory.seedSku(
          "alice",
          new Sku(
              sku.skuId(),
              sku.game(),
              sku.externalId(),
              sku.name(),
              sku.setCode(),
              sku.setName(),
              sku.collectorNumber(),
              sku.finish(),
              sku.condition(),
              missingPrice ? null : sku.price(),
              missingPrice ? sku.fetchtcgCardId() : null,
              sku.fetchtcgSetId(),
              sku.version()),
          true);
      inventory.seedUnits(
          "alice",
          "sku",
          List.of(new Unit(1, "A1", UnitStatus.IN_STOCK, List.of(), 1L, null, null)));
      assertThatThrownBy(() -> service.updateListing(ctx, new UpdateListingRequest("sku")))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void updateListingContractsShouldUseSkuIdAndEmptyResponse() throws Exception {
    // arrange
    var mapper = new ObjectMapper();

    // act / assert
    assertThat(mapper.readValue("{\"sku_id\":\"sku\"}", UpdateListingRequest.class))
        .isEqualTo(new UpdateListingRequest("sku"));
    assertThat(mapper.writeValueAsString(new UpdateListingResponse())).isEqualTo("{}");
  }

  @Test
  void updateListingShouldMapEveryConditionWithoutPhotos() throws Exception {
    for (var entry :
        Map.of(
                Condition.NM,
                "raw-nm",
                Condition.LP,
                "raw-lp",
                Condition.MP,
                "raw-mp",
                Condition.HP,
                "raw-hp",
                Condition.DMG,
                "raw-d")
            .entrySet()) {
      // arrange
      var condition = entry.getKey();
      var providerCondition = entry.getValue();
      var inventory = new FakeInventoryService();
      var provider = mock(FetchTcgService.class);
      var sku = listingSku();
      inventory.seedSku(
          "alice",
          new Sku(
              sku.skuId(),
              sku.game(),
              sku.externalId(),
              sku.name(),
              sku.setCode(),
              sku.setName(),
              sku.collectorNumber(),
              sku.finish(),
              condition,
              new BigDecimal("50.00"),
              sku.fetchtcgCardId(),
              sku.fetchtcgSetId(),
              sku.version()),
          true);
      inventory.seedUnits(
          "alice",
          "sku",
          List.of(new Unit(1, "A1", UnitStatus.IN_STOCK, List.of(), 1L, null, null)));
      when(provider.updateListing(any(), any()))
          .thenReturn(
              new com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingResponse(42, 1));
      var photos = mock(PhotosService.class);
      var ctx = new RequestContext("alice");

      // act
      new UpdateListingService(listingRepository, inventory, photos, provider)
          .updateListing(ctx, new UpdateListingRequest("sku"));

      // assert
      verify(provider)
          .updateListing(
              ctx,
              new com.jordansimsmith.tcginventory.fetchtcg.client.UpdateListingRequest(
                  "card", providerCondition, 1, new BigDecimal("50.00"), null, List.of()));
      verifyNoInteractions(photos);
    }
  }

  private Sku listingSku() {
    return new Sku(
        "sku",
        "mtg",
        "external",
        "Card",
        "SET",
        "Set",
        "1",
        "normal",
        Condition.NM,
        new BigDecimal("2.00"),
        "card",
        1,
        7);
  }
}
