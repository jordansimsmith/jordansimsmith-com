package com.jordansimsmith.tcginventory.orders.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbContainer;
import com.jordansimsmith.dynamodb.DynamoDbUtils;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.fetchtcg.client.BuyerAddress;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindOffersRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindOffersResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.Offer;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferItem;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferListing;
import com.jordansimsmith.tcginventory.fetchtcg.client.ShippingOption;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.listings.client.FakeListingService;
import com.jordansimsmith.tcginventory.listings.client.Listing;
import com.jordansimsmith.tcginventory.listings.client.UpdateListingRequest;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrdersRequest;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrdersResponse;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@Testcontainers
public class UpdateOrdersServiceIntegrationTest {
  private static final String TABLE_NAME = "tcg_inventory";
  private static final String USER = "orders-user";
  private static final String SKU_ID = "mtg#scryfall#card-1#normal#NM";
  private static final String ORDER_ID = "83663";
  private static final int LISTING_ID = 101;
  private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);

  @Container private static final DynamoDbContainer dynamoDbContainer = new DynamoDbContainer();

  private TcgInventoryTestFactory factory;
  private FakeClock clock;
  private FakeFetchTcgService fetchTcgService;
  private FakeListingService listingService;
  private DynamoDbTable<OrderItem> orderTable;
  private DynamoDbTable<OrderRowItem> rowTable;
  private OrderReadRepository orderReadRepository;
  private OrderMutationRepository orderMutationRepository;
  private OrderSettingsRepository orderSettingsRepository;

  @BeforeAll
  static void createTable() {
    var factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    DynamoDbUtils.createTable(factory.dynamoDbClient(), factory.tableDefinition());
  }

  @BeforeEach
  void setUp() {
    factory =
        TcgInventoryTestFactory.create(
            dynamoDbContainer.getEndpoint(), TcgInventoryTestFactory.UNUSED_S3_ENDPOINT);
    clock = factory.fakeClock();
    clock.setTime(NOW);
    fetchTcgService = factory.fakeFetchTcgService();
    listingService = new FakeListingService(factory.inventoryService());
    DynamoDbUtils.reset(factory.dynamoDbClient());
    fetchTcgService.reset();

    var enhanced = factory.dynamoDbEnhancedClient();
    orderTable = enhanced.table(TABLE_NAME, TableSchema.fromBean(OrderItem.class));
    rowTable = enhanced.table(TABLE_NAME, TableSchema.fromBean(OrderRowItem.class));
    var settingsTable = enhanced.table(TABLE_NAME, TableSchema.fromBean(OrderSettingsItem.class));
    orderReadRepository = new OrderReadRepository(orderTable, rowTable);
    orderMutationRepository =
        new OrderMutationRepository(
            orderTable, rowTable, factory.dynamoDbClient(), TABLE_NAME, clock);
    orderSettingsRepository = new OrderSettingsRepository(settingsTable);
  }

  @Test
  void retriesReservationWhenInventoryCommittedBeforeOrderProgress() throws Exception {
    seedSkuAndUnit();
    fetchTcgService.seedOffers(
        USER,
        List.of(acceptedOffer(ORDER_ID, new OfferItem(offerListing(), 1, new BigDecimal("2.00")))));

    var mutationRepository = spy(orderMutationRepository);
    doThrow(new IllegalStateException("progress unavailable"))
        .doCallRealMethod()
        .when(mutationRepository)
        .recordReservation(USER, ORDER_ID, 0, 0, 1);
    var service = createService(fetchTcgService, mutationRepository);
    var ctx = new RequestContext(USER);

    assertThatThrownBy(() -> service.updateOrders(ctx, new UpdateOrdersRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("progress unavailable");
    assertThat(getUnit().status()).isEqualTo(UnitStatus.RESERVED);
    assertThat(orderReadRepository.findAllOrders(USER))
        .singleElement()
        .satisfies(
            order -> {
              assertThat(order.getStatus()).isEqualTo("reserving");
              assertThat(
                      orderReadRepository
                          .findRows(USER, order)
                          .getFirst()
                          .getAllocatedSequenceNumbers())
                  .isEmpty();
            });

    var versionAfterFirstReservation =
        factory.inventoryService().getSku(ctx, new GetSkuRequest(SKU_ID)).sku().version();
    service.updateOrders(ctx, new UpdateOrdersRequest());

    assertThat(orderReadRepository.findAllOrders(USER))
        .singleElement()
        .satisfies(
            order -> {
              assertThat(order.getStatus()).isEqualTo("awaiting_payment");
              assertThat(
                      orderReadRepository
                          .findRows(USER, order)
                          .getFirst()
                          .getAllocatedSequenceNumbers())
                  .containsExactly(1);
            });
    assertThat(factory.inventoryService().getSku(ctx, new GetSkuRequest(SKU_ID)).sku().version())
        .isEqualTo(versionAfterFirstReservation);
  }

  @Test
  void laterOfferPageFailurePreventsAllOrderEffects() throws Exception {
    seedSkuAndUnit();
    var ctx = new RequestContext(USER);
    var firstPage =
        new FindOffersResponse(
            List.of(
                acceptedOffer(ORDER_ID, new OfferItem(offerListing(), 1, new BigDecimal("2.00")))),
            "next");
    var failure = new ServiceUnavailableException("offers unavailable");
    var fetch = mock(FetchTcgService.class);
    when(fetch.findOffers(ctx, new FindOffersRequest(100, null))).thenReturn(firstPage);
    when(fetch.findOffers(ctx, new FindOffersRequest(100, "next"))).thenThrow(failure);

    assertThatThrownBy(
            () ->
                createService(fetch, orderMutationRepository)
                    .updateOrders(ctx, new UpdateOrdersRequest()))
        .isSameAs(failure);

    assertThat(orderReadRepository.findAllOrders(USER)).isEmpty();
    assertThat(getUnit().status()).isEqualTo(UnitStatus.IN_STOCK);
  }

  @Test
  void updateOrdersRequestAndResponseHaveEmptyJsonShapes() throws Exception {
    var mapper = new ObjectMapper();

    assertThat(mapper.writeValueAsString(new UpdateOrdersRequest())).isEqualTo("{}");
    assertThat(mapper.writeValueAsString(new UpdateOrdersResponse())).isEqualTo("{}");
    assertThat(mapper.readValue("{}", UpdateOrdersRequest.class))
        .isEqualTo(new UpdateOrdersRequest());
  }

  private UpdateOrdersService createService(
      FetchTcgService fetchTcgService, OrderMutationRepository mutationRepository) {
    return new UpdateOrdersService(
        orderReadRepository,
        mutationRepository,
        orderSettingsRepository,
        fetchTcgService,
        listingService,
        factory.inventoryService(),
        clock);
  }

  private void seedSkuAndUnit() {
    factory
        .fakeInventoryService()
        .seedSku(
            USER,
            new Sku(
                SKU_ID,
                "mtg",
                "card-1",
                "Card",
                "dom",
                "Dominaria",
                "1",
                "normal",
                Condition.NM,
                new BigDecimal("2.00"),
                "fetchtcg-card-1",
                2,
                1),
            false);
    factory
        .fakeInventoryService()
        .seedUnits(
            USER,
            SKU_ID,
            List.of(
                new Unit(
                    1, "A0-1", UnitStatus.IN_STOCK, List.of(), NOW.getEpochSecond(), null, null)));
    listingService.seedListing(USER, new Listing(LISTING_ID, SKU_ID));
  }

  private Unit getUnit() throws Exception {
    return factory
        .inventoryService()
        .findUnits(new RequestContext(USER), new FindUnitsRequest(SKU_ID, 100, null))
        .units()
        .getFirst();
  }

  private OfferListing offerListing() {
    return new OfferListing(LISTING_ID, "NM", new BigDecimal("2.00"));
  }

  private Offer acceptedOffer(String orderId, OfferItem item) {
    return new Offer(
        Long.parseLong(orderId),
        "ACCEPTED",
        null,
        null,
        "PICKUP",
        null,
        null,
        null,
        new BigDecimal("2.00"),
        List.of(item));
  }

  @Test
  void updateOrdersShouldNotAdvanceWhenAwaitingPayment() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    createExistingOrder("jordan", "91101", "awaiting_payment");

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    91101,
                    "ACCEPTED",
                    "AWAITING_PAYMENT",
                    "2026-08-28T10:50:05.986+0000",
                    "DELIVERY",
                    null,
                    null,
                    null,
                    new BigDecimal("5.00"),
                    List.of())));

    // act
    updateOrders("jordan");

    // assert
    var order = getOrder("jordan", "91101");
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");
  }

  @Test
  void updateOrdersShouldCreateOrderAcceptedAfterCutoff() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);
    createTrackOrdersAfter("jordan", Instant.parse("2026-08-10T00:00:00Z"));

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    83663,
                    "ACCEPTED",
                    null,
                    "2026-08-11T04:42:12.476+0000",
                    "PICKUP",
                    null,
                    null,
                    null,
                    new BigDecimal("3.33"),
                    List.of(
                        new OfferItem(
                            new OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
                            2,
                            new BigDecimal("1.50"))))));

    // act
    updateOrders("jordan");

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order).isNotNull();
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");
  }

  @Test
  void updateOrdersShouldSkipOffersWithNullAcceptedAtWhenCutoffSet() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);
    createTrackOrdersAfter("jordan", Instant.parse("2026-08-01T00:00:00Z"));

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    83663,
                    "ACCEPTED",
                    null,
                    null,
                    "PICKUP",
                    null,
                    null,
                    null,
                    new BigDecimal("3.33"),
                    List.of(
                        new OfferItem(
                            new OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
                            2,
                            new BigDecimal("1.50"))))));

    // act
    updateOrders("jordan");

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order).isNull();
  }

  @Test
  void updateOrdersShouldReserveMixedMagicAndPokemonOffer() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    var magicSkuId = "mtg#scryfall#scryfall-1#normal#NM";
    var pokemonSkuId = "pokemon#tcgplayer#283917#reverse_holofoil#NM";
    createSkuWithUnits("jordan", magicSkuId, 1001, 1);
    createInventorySku("jordan", pokemonSkuId, "fetch-card-9002", "12.00", true);
    seedUnits("jordan", pokemonSkuId, List.of(inStockUnit(1)));
    synchronizeListing("jordan", pokemonSkuId, 2002);
    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    83663,
                    "ACCEPTED",
                    null,
                    "2026-08-11T04:42:12.476+0000",
                    "PICKUP",
                    null,
                    null,
                    null,
                    new BigDecimal("14.00"),
                    List.of(
                        new OfferItem(
                            new OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
                            1,
                            new BigDecimal("2.00")),
                        new OfferItem(
                            new OfferListing(2002, "raw-nm", new BigDecimal("12.00")),
                            1,
                            new BigDecimal("12.00"))))));

    // act
    updateOrders("jordan");

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");
    assertThat(findOrderRows(order))
        .extracting(OrderRowItem::getSkuId)
        .containsExactly(magicSkuId, pokemonSkuId);
    assertThat(getUnits("jordan", magicSkuId))
        .singleElement()
        .satisfies(
            unit -> {
              assertThat(unit.status()).isEqualTo(UnitStatus.RESERVED);
              assertThat(unit.reservationId()).isEqualTo("83663");
            });
    assertThat(getUnits("jordan", pokemonSkuId))
        .singleElement()
        .satisfies(
            unit -> {
              assertThat(getSku("jordan", pokemonSkuId).game()).isEqualTo("pokemon");
              assertThat(unit.status()).isEqualTo(UnitStatus.RESERVED);
              assertThat(unit.reservationId()).isEqualTo("83663");
            });
  }

  @Test
  void updateOrdersShouldRefreshFulfillmentDetailsOnExistingOrders() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    createExistingOrder("jordan", "91329", "awaiting_payment");

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    91329,
                    "ACCEPTED",
                    "SEND_TRACKING_CODE",
                    "2026-08-29T03:03:55.019+0000",
                    "DELIVERY",
                    "Chris Andrew (generic)",
                    new BuyerAddress(
                        "32 Abercrombie Street", null, "Howick", "Auckland", "2014", "NZ"),
                    new ShippingOption("Economy Tracked"),
                    new BigDecimal("61.50"),
                    List.of())));

    // act
    updateOrders("jordan");

    // assert
    var order = getOrder("jordan", "91329");
    assertThat(order.getBuyerName()).isEqualTo("Chris Andrew (generic)");
    assertThat(order.getPostageOption()).isEqualTo("Economy Tracked");
    assertThat(order.getBuyerAddress())
        .isEqualTo(
            OrderItem.BuyerAddress.create(
                "32 Abercrombie Street", null, "Howick", "Auckland", "2014", "NZ"));
  }

  @Test
  void updateOrdersShouldStoreNoAddressWhenEveryPartIsBlank() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    createExistingOrder("jordan", "83663", "awaiting_payment");

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    83663,
                    "ACCEPTED",
                    "AWAITING_PAYMENT",
                    "2026-08-11T04:42:12.476+0000",
                    "PICKUP",
                    "Ben Creagh (sideswipe)",
                    new BuyerAddress(null, "", null, null, null, null),
                    null,
                    new BigDecimal("3.33"),
                    List.of())));

    // act
    updateOrders("jordan");

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getBuyerName()).isEqualTo("Ben Creagh (sideswipe)");
    assertThat(order.getBuyerAddress()).isNull();
    assertThat(order.getPostageOption()).isNull();
  }

  @Test
  void updateOrdersShouldSkipOffersBeforeCutoff() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);
    createTrackOrdersAfter("jordan", Instant.parse("2026-08-15T00:00:00Z"));

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    83663,
                    "ACCEPTED",
                    null,
                    "2026-08-11T04:42:12.476+0000",
                    "PICKUP",
                    null,
                    null,
                    null,
                    new BigDecimal("3.33"),
                    List.of(
                        new OfferItem(
                            new OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
                            2,
                            new BigDecimal("1.50"))))));

    // act
    updateOrders("jordan");

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order).isNull();

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    assertThat(units.stream().allMatch(u -> u.status() == UnitStatus.IN_STOCK)).isTrue();
  }

  @Test
  void updateOrdersShouldNotVoidPaidOrder() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 2);
    createReservedOrder("jordan", "83663", "to_pick", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1);

    factory
        .fakeFetchTcgService()
        .seedOffers("jordan", List.of(cancelledOffer(83663, "CANCELLED_BY_SELLER")));

    // act
    updateOrders("jordan");

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("to_pick");

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    var reserved = units.stream().filter(u -> u.status() == UnitStatus.RESERVED).toList();
    assertThat(reserved).hasSize(1);
  }

  @Test
  void updateOrdersShouldBeIdempotentOnReprocessing() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    createExistingOrder("jordan", "83663", "awaiting_payment");

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    83663,
                    "ACCEPTED",
                    null,
                    "2026-08-11T04:42:12.476+0000",
                    "PICKUP",
                    null,
                    null,
                    null,
                    new BigDecimal("3.33"),
                    List.of())));

    // act
    updateOrders("jordan");

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");
  }

  @Test
  void updateOrdersShouldAdvanceToPickOnTrackingCode() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    createExistingOrder("jordan", "91329", "awaiting_payment");

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    91329,
                    "ACCEPTED",
                    "SEND_TRACKING_CODE",
                    "2026-08-29T03:03:55.019+0000",
                    "DELIVERY",
                    null,
                    null,
                    null,
                    new BigDecimal("61.50"),
                    List.of())));

    // act
    updateOrders("jordan");

    // assert
    var order = getOrder("jordan", "91329");
    assertThat(order.getStatus()).isEqualTo("to_pick");
    assertThat(order.getFetchtcgCurrentAction()).isEqualTo("SEND_TRACKING_CODE");
  }

  @Test
  void updateOrdersShouldAdvanceToPickOnPayment() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    createExistingOrder("jordan", "83663", "awaiting_payment");

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    83663,
                    "ACCEPTED",
                    "SEND_PICKUP_ADDRESS",
                    "2026-08-11T04:42:12.476+0000",
                    "PICKUP",
                    null,
                    null,
                    null,
                    new BigDecimal("3.33"),
                    List.of())));

    // act
    updateOrders("jordan");

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("to_pick");
    assertThat(order.getFetchtcgCurrentAction()).isEqualTo("SEND_PICKUP_ADDRESS");

    updateOrders("jordan");

    var replayedOrder = getOrder("jordan", "83663");
    assertThat(replayedOrder.getStatus()).isEqualTo("to_pick");
    assertThat(replayedOrder.getFetchtcgCurrentAction()).isEqualTo("SEND_PICKUP_ADDRESS");
  }

  @Test
  void updateOrdersShouldNotRewriteUnchangedFulfillmentDetails() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    createExistingOrder("jordan", "83663", "to_pick");
    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    83663,
                    "ACCEPTED",
                    "SEND_REVIEW",
                    "2026-08-11T04:42:12.476+0000",
                    "PICKUP",
                    "Ben Creagh (sideswipe)",
                    null,
                    null,
                    new BigDecimal("3.33"),
                    List.of())));

    clock.setTime(Instant.ofEpochSecond(1700005000));
    updateOrders("jordan");
    assertThat(getOrder("jordan", "83663").getUpdatedAt())
        .isEqualTo(Instant.ofEpochSecond(1700005000));

    // act
    clock.setTime(Instant.ofEpochSecond(1700009999));
    updateOrders("jordan");

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getBuyerName()).isEqualTo("Ben Creagh (sideswipe)");
    assertThat(order.getUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1700005000));
  }

  @Test
  void updateOrdersShouldCreateOrderWhenCutoffNotSet() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 3);

    factory
        .fakeFetchTcgService()
        .seedOffers(
            "jordan",
            List.of(
                new Offer(
                    83663,
                    "ACCEPTED",
                    null,
                    "2026-08-11T04:42:12.476+0000",
                    "PICKUP",
                    null,
                    null,
                    null,
                    new BigDecimal("3.33"),
                    List.of(
                        new OfferItem(
                            new OfferListing(1001, "raw-nm", new BigDecimal("2.00")),
                            2,
                            new BigDecimal("1.50"))))));

    // act
    updateOrders("jordan");

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order).isNotNull();
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");
  }

  @Test
  void updateOrdersShouldFailForMissingReservingOffer() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    var skuId = "mtg#scryfall#scryfall-1#normal#NM";
    createSkuWithUnits("jordan", skuId, 1001, 3);
    var firstUnit = getUnits("jordan", skuId).get(0);
    seedUnitState("jordan", skuId, firstUnit, UnitStatus.RESERVED, "83663");
    orderTable.putItem(
        createOrderItem(
            "jordan",
            "83663",
            "reserving",
            "ACCEPTED",
            null,
            "PICKUP",
            null,
            null,
            null,
            "3.33",
            List.of(new OrderRowItem(skuId, 1001, 2, "3.00", "2.00", List.of(1))),
            Instant.ofEpochSecond(1700000000)));

    // act & assert
    assertThatThrownBy(() -> updateOrders("jordan"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FetchTCG offer not found for order 83663");
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("reserving");
    assertThat(findOrderRows(order).getFirst().getAllocatedSequenceNumbers()).containsExactly(1);
    assertThat(getUnits("jordan", skuId))
        .filteredOn(unit -> unit.status() == UnitStatus.RESERVED)
        .hasSize(1);
  }

  @Test
  void updateOrdersShouldThrowForMissingOffer() throws Exception {
    // arrange
    clock.setTime(Instant.ofEpochSecond(1700000000));
    createSkuWithUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM", 1001, 2);
    createReservedOrder(
        "jordan", "83663", "awaiting_payment", "mtg#scryfall#scryfall-1#normal#NM", 1001, 1);

    factory.fakeFetchTcgService().seedOffers("jordan", List.of());

    // act
    assertThatThrownBy(() -> updateOrders("jordan"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("FetchTCG offer not found for order 83663");

    // assert
    var order = getOrder("jordan", "83663");
    assertThat(order.getStatus()).isEqualTo("awaiting_payment");

    var units = getUnits("jordan", "mtg#scryfall#scryfall-1#normal#NM");
    var reserved = units.stream().filter(u -> u.status() == UnitStatus.RESERVED).toList();
    assertThat(reserved).hasSize(1);
    assertThat(reserved.get(0).reservationId()).isEqualTo("83663");
  }

  private void updateOrders(String user) throws ServiceException {
    factory.orderService().updateOrders(new RequestContext(user), new UpdateOrdersRequest());
  }

  private void createSkuWithUnits(String user, String skuId, int fetchTcgListingId, int unitCount) {
    createInventorySku(user, skuId, "mtg_168_c_dom_normal", "1.50", true);
    seedUnits(
        user,
        skuId,
        IntStream.rangeClosed(1, unitCount).mapToObj(sequence -> inStockUnit(sequence)).toList());
    synchronizeListing(user, skuId, fetchTcgListingId);
  }

  private Sku createInventorySku(
      String user, String skuId, String fetchTcgCardId, String suggestedPrice, boolean dirty) {
    var parts = skuId.split("#");
    var game = parts[0];
    var externalId = parts[2];
    var finish = parts[3];
    var condition = Condition.valueOf(parts[4]);
    var sku =
        new Sku(
            skuId,
            game,
            externalId,
            "Test Card",
            "dom",
            "Dominaria",
            "168",
            finish,
            condition,
            suggestedPrice == null ? null : new BigDecimal(suggestedPrice),
            fetchTcgCardId,
            "mtg".equals(game) ? 2624 : 1,
            1);
    factory.fakeInventoryService().seedSku(user, sku, dirty);
    return sku;
  }

  private Unit inStockUnit(int sequenceNumber) {
    return new Unit(
        sequenceNumber,
        "A0-" + sequenceNumber,
        UnitStatus.IN_STOCK,
        List.of(),
        Instant.ofEpochSecond(1700000000).getEpochSecond(),
        null,
        null);
  }

  private void seedUnits(String user, String skuId, List<Unit> units) {
    factory.fakeInventoryService().seedUnits(user, skuId, units);
  }

  private void seedUnitState(
      String user, String skuId, Unit current, UnitStatus status, String reservationId) {
    var updated =
        new Unit(
            current.sequenceNumber(),
            current.location(),
            status,
            current.photos(),
            current.createdAt(),
            current.updatedAt(),
            reservationId);
    var units = new ArrayList<>(getUnits(user, skuId));
    units.replaceAll(unit -> unit.sequenceNumber() == current.sequenceNumber() ? updated : unit);
    seedUnits(user, skuId, units);
  }

  private void createTrackOrdersAfter(String user, Instant trackOrdersAfter) {
    factory
        .dynamoDbClient()
        .putItem(
            PutItemRequest.builder()
                .tableName("tcg_inventory")
                .item(
                    Map.of(
                        "pk", AttributeValue.fromS("USER#" + user),
                        "sk", AttributeValue.fromS("ORDER_SETTINGS"),
                        "user", AttributeValue.fromS(user),
                        "track_orders_after",
                            AttributeValue.fromN(Long.toString(trackOrdersAfter.getEpochSecond()))))
                .build());
  }

  private void createExistingOrder(String user, String offerId, String status) {
    var order =
        createOrderItem(
            user,
            offerId,
            status,
            "ACCEPTED",
            null,
            "PICKUP",
            null,
            null,
            null,
            "3.33",
            List.of(),
            Instant.ofEpochSecond(1700000000));
    orderTable.putItem(order);
  }

  private void createReservedOrder(
      String user,
      String offerId,
      String status,
      String skuId,
      int fetchtcgListingId,
      int... sequenceNumbers) {
    var allocated = Arrays.stream(sequenceNumbers).boxed().toList();
    for (var sequenceNumber : allocated) {
      reserveUnit(user, skuId, sequenceNumber, offerId);
    }

    createOrderWithLines(
        user,
        offerId,
        status,
        List.of(
            new OrderRowItem(
                skuId, fetchtcgListingId, allocated.size(), "3.33", "4.20", allocated)));
  }

  private void createOrderWithLines(
      String user, String offerId, String status, List<OrderRowItem> lines) {
    var order =
        createOrderItem(
            user,
            offerId,
            status,
            "ACCEPTED",
            "AWAITING_PAYMENT",
            "PICKUP",
            null,
            null,
            null,
            "3.33",
            lines,
            Instant.ofEpochSecond(1700000000));
    orderTable.putItem(order);
  }

  private void reserveUnit(String user, String skuId, int sequenceNumber, String orderId) {
    seedUnitState(user, skuId, getUnit(user, skuId, sequenceNumber), UnitStatus.RESERVED, orderId);
  }

  private Unit getUnit(String user, String skuId, int sequenceNumber) {
    return getUnits(user, skuId).stream()
        .filter(unit -> unit.sequenceNumber() == sequenceNumber)
        .findFirst()
        .orElseThrow();
  }

  private static Offer cancelledOffer(int offerId, String status) {
    return new Offer(
        offerId,
        status,
        null,
        "2026-08-11T04:42:12.476+0000",
        "PICKUP",
        null,
        null,
        null,
        new BigDecimal("3.33"),
        List.of());
  }

  private OrderItem getOrder(String user, String offerId) {
    return orderTable.getItem(
        Key.builder()
            .partitionValue(OrderItem.formatPk(user))
            .sortValue(OrderItem.formatSk(offerId))
            .build());
  }

  private Sku getSku(String user, String skuId) {
    try {
      return factory
          .inventoryService()
          .getSku(new RequestContext(user), new GetSkuRequest(skuId))
          .sku();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private List<Unit> getUnits(String user, String skuId) {
    try {
      var units = new ArrayList<Unit>();
      String continuation = null;
      do {
        var response =
            factory
                .inventoryService()
                .findUnits(
                    new RequestContext(user), new FindUnitsRequest(skuId, 100, continuation));
        units.addAll(response.units());
        continuation = response.nextContinuation();
      } while (continuation != null);
      return units;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void synchronizeListing(String user, String skuId, int listingId) {
    var sku = getSku(user, skuId);
    factory
        .fakeFetchTcgService()
        .seedListingId(
            user, sku.fetchtcgCardId(), "raw-" + sku.condition().name().toLowerCase(), listingId);
    try {
      factory
          .listingService()
          .updateListing(new RequestContext(user), new UpdateListingRequest(skuId));
    } catch (ServiceException e) {
      throw new RuntimeException(e);
    }
  }

  private OrderItem createOrderItem(
      String user,
      String orderId,
      String status,
      String providerStatus,
      String action,
      String delivery,
      String buyer,
      OrderItem.BuyerAddress address,
      String postage,
      String total,
      List<OrderRowItem> lines,
      Instant createdAt) {
    var table =
        factory
            .dynamoDbEnhancedClient()
            .table(TABLE_NAME, TableSchema.fromBean(OrderRowItem.class));
    for (int position = 0; position < lines.size(); position++) {
      var line = lines.get(position);
      table.putItem(
          OrderRowItem.create(
              user,
              orderId,
              position,
              line.getSkuId(),
              line.getFetchtcgListingId(),
              line.getQuantity(),
              line.getPrice(),
              line.getListedPrice(),
              line.getAllocatedSequenceNumbers()));
    }
    return OrderItem.create(
        user,
        orderId,
        status,
        providerStatus,
        action,
        delivery,
        buyer,
        address,
        postage,
        total,
        lines.size(),
        createdAt);
  }

  private List<OrderRowItem> findOrderRows(OrderItem order) {
    return factory
        .dynamoDbEnhancedClient()
        .table(TABLE_NAME, TableSchema.fromBean(OrderRowItem.class))
        .query(
            request ->
                request
                    .queryConditional(
                        QueryConditional.keyEqualTo(
                            Key.builder()
                                .partitionValue(order.getPk() + "#ORDER#" + order.getOrderId())
                                .build()))
                    .consistentRead(true))
        .items()
        .stream()
        .toList();
  }
}
