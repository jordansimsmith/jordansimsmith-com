package com.jordansimsmith.tcginventory.listings.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.listings.client.FindListingsRequest;
import com.jordansimsmith.tcginventory.listings.client.FindListingsResponse;
import com.jordansimsmith.tcginventory.listings.client.Listing;
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
}
