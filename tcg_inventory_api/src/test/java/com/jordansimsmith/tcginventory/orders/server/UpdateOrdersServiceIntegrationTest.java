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
import com.jordansimsmith.service.ServiceUnavailableException;
import com.jordansimsmith.tcginventory.TcgInventoryTestFactory;
import com.jordansimsmith.tcginventory.fetchtcg.client.FakeFetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindOffersRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindOffersResponse;
import com.jordansimsmith.tcginventory.fetchtcg.client.Offer;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferItem;
import com.jordansimsmith.tcginventory.fetchtcg.client.OfferListing;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import com.jordansimsmith.tcginventory.inventory.client.FindUnitsRequest;
import com.jordansimsmith.tcginventory.inventory.client.GetSkuRequest;
import com.jordansimsmith.tcginventory.inventory.client.Sku;
import com.jordansimsmith.tcginventory.inventory.client.Unit;
import com.jordansimsmith.tcginventory.inventory.client.UnitStatus;
import com.jordansimsmith.tcginventory.listings.client.FakeListingService;
import com.jordansimsmith.tcginventory.listings.client.Listing;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrdersRequest;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrdersResponse;
import com.jordansimsmith.time.FakeClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

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
}
