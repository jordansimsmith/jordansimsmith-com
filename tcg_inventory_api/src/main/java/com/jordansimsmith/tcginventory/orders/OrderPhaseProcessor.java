package com.jordansimsmith.tcginventory.orders;

import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgClient;
import com.jordansimsmith.tcginventory.games.Games;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.tcginventory.settings.SettingsItem;
import com.jordansimsmith.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class OrderPhaseProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(OrderPhaseProcessor.class);

  // observed currentAction values after CONFIRM_PAYMENT_RECEIVED, per delivery mode:
  // pickup:   SEND_PICKUP_ADDRESS -> SEND_REVIEW -> AWAIT_REVIEW
  // delivery: SEND_TRACKING_CODE -> SEND_REVIEW -> AWAIT_REVIEW
  private static final Set<String> PAYMENT_ACTIONS =
      Set.of("SEND_PICKUP_ADDRESS", "SEND_TRACKING_CODE", "SEND_REVIEW", "AWAIT_REVIEW");

  // the post-acceptance members of FetchTCG's cancelled filter; its other members (REJECTED,
  // WITHDRAWN_BY_BUYER) resolve before acceptance and never produce orders
  private static final Set<String> CANCELLED_STATUSES =
      Set.of("CANCELLED_BY_SELLER", "CANCELLED_BY_BUYER");

  private static final DateTimeFormatter ACCEPTED_AT_FORMATTER =
      new DateTimeFormatterBuilder()
          .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
          .optionalStart()
          .appendFraction(ChronoField.MILLI_OF_SECOND, 0, 3, true)
          .optionalEnd()
          .appendOffset("+HHmm", "Z")
          .toFormatter();

  private record Fulfillment(
      @Nullable String buyerName,
      @Nullable OrderItem.BuyerAddress buyerAddress,
      @Nullable String postageOption) {}

  private record AllocatedUnit(String skuId, int sequenceNumber) {}

  private record PlannedReservation(
      FetchTcgClient.SellerOffer offer, List<OrderItem.OrderLine> lines) {}

  public static class ReservationPreflightException extends RuntimeException {
    public ReservationPreflightException(String message) {
      super(message);
    }
  }

  private final DynamoDbTable<OrderItem> orderTable;
  private final DynamoDbTable<SkuItem> skuTable;
  private final DynamoDbTable<SettingsItem> settingsTable;
  private final OrderRepository orderRepository;
  private final InventoryRepository inventoryRepository;
  private final Clock clock;
  private final FetchTcgClient fetchTcgClient;

  public OrderPhaseProcessor(
      DynamoDbTable<OrderItem> orderTable,
      DynamoDbTable<SkuItem> skuTable,
      DynamoDbTable<SettingsItem> settingsTable,
      OrderRepository orderRepository,
      InventoryRepository inventoryRepository,
      Clock clock,
      FetchTcgClient fetchTcgClient) {
    this.orderTable = orderTable;
    this.skuTable = skuTable;
    this.settingsTable = settingsTable;
    this.orderRepository = orderRepository;
    this.inventoryRepository = inventoryRepository;
    this.clock = clock;
    this.fetchTcgClient = fetchTcgClient;
  }

  public void process(String user, String bearerToken) {
    for (var order : loadExistingOrders(user)) {
      if ("reserving".equals(order.getStatus())) {
        resumeReservingOrder(user, order);
      } else if ("voiding".equals(order.getStatus())) {
        releaseVoidingOrder(user, order);
      }
    }

    var allOffers = paginateOffers(bearerToken);
    LOGGER.info("fetched {} offers from FetchTCG for user {}", allOffers.size(), user);

    if (!allOffers.isEmpty()) {
      var statusCounts =
          allOffers.stream().collect(Collectors.groupingBy(o -> o.status(), Collectors.counting()));
      LOGGER.info("offer statuses: {}", statusCounts);
    }

    var trackOrdersAfter = loadTrackOrdersAfter(user);
    if (trackOrdersAfter != null) {
      LOGGER.info("track_orders_after is set to {}", trackOrdersAfter);
    }

    var offerMap =
        allOffers.stream().collect(Collectors.toMap(o -> String.valueOf(o.id()), o -> o));

    var existingOrders = loadExistingOrders(user);
    LOGGER.info("found {} existing orders in DynamoDB", existingOrders.size());

    var listingToSkuId = buildListingToSkuMap(user);
    LOGGER.info("built listing-to-sku map with {} entries", listingToSkuId.size());

    int advancedCount = 0;
    int voidedCount = 0;
    int refreshedCount = 0;
    for (var order : existingOrders) {
      // an order missing from the list keeps its reservations: a truncated page must never
      // release stock
      var offer = offerMap.get(order.getOrderId());
      if (offer == null) {
        continue;
      }

      var cancelled = offer.status() != null && CANCELLED_STATUSES.contains(offer.status());
      var paymentReceived =
          offer.currentAction() != null && PAYMENT_ACTIONS.contains(offer.currentAction());
      if ("awaiting_payment".equals(order.getStatus())) {
        if (cancelled) {
          orderRepository.startVoiding(user, order, offer.status());
          releaseVoidingOrder(user, order);
          voidedCount++;
        } else if (paymentReceived) {
          orderRepository.advanceOrderToPickReady(
              user, order.getOrderId(), offer.status(), offer.currentAction());
          advancedCount++;
        }
      }

      // the buyer supplies an address and picks a postage option after the offer is accepted, so
      // unlike the priced line data these are mirrored on every run rather than frozen at ingest
      var fulfillment = toFulfillment(offer);
      var stored =
          new Fulfillment(order.getBuyerName(), order.getBuyerAddress(), order.getPostageOption());
      if (!fulfillment.equals(stored)) {
        orderRepository.updateOrderFulfillment(
            user,
            order.getOrderId(),
            fulfillment.buyerName(),
            fulfillment.buyerAddress(),
            fulfillment.postageOption());
        refreshedCount++;
      }
    }
    LOGGER.info(
        "advanced {} orders to pick-ready, voided {} cancelled orders, refreshed fulfillment"
            + " details on {}",
        advancedCount,
        voidedCount,
        refreshedCount);

    var existingOrderIds =
        existingOrders.stream().map(OrderItem::getOrderId).collect(Collectors.toSet());
    var newOffers =
        allOffers.stream()
            .filter(offer -> "ACCEPTED".equals(offer.status()))
            .filter(offer -> trackOrdersAfter == null || isAfterCutoff(offer, trackOrdersAfter))
            .filter(offer -> !existingOrderIds.contains(String.valueOf(offer.id())))
            .toList();

    var plans = planReservations(user, newOffers, listingToSkuId);
    for (var plan : plans) {
      var offer = plan.offer();
      var fulfillment = toFulfillment(offer);
      var order =
          OrderItem.create(
              user,
              String.valueOf(offer.id()),
              "reserving",
              offer.status(),
              offer.currentAction(),
              offer.deliveryMode(),
              fulfillment.buyerName(),
              fulfillment.buyerAddress(),
              fulfillment.postageOption(),
              offer.totalOfferPrice() != null ? offer.totalOfferPrice().toPlainString() : null,
              plan.lines(),
              clock.now());
      orderRepository.createReservingOrder(order);
      resumeReservingOrder(user, order);
    }
    LOGGER.info("created {} new orders", plans.size());
  }

  private List<FetchTcgClient.SellerOffer> paginateOffers(String bearerToken) {
    var allOffers = new ArrayList<FetchTcgClient.SellerOffer>();
    int page = 0;
    while (true) {
      var response = fetchTcgClient.getSellerOffers(bearerToken, page);
      allOffers.addAll(response.content());
      page++;
      if (page >= response.totalPages()) {
        break;
      }
    }
    return allOffers;
  }

  private Instant loadTrackOrdersAfter(String user) {
    var key =
        Key.builder()
            .partitionValue(SkuItem.formatUserPk(user))
            .sortValue(SettingsItem.formatSk())
            .build();
    var settingsItem = settingsTable.getItem(key);
    if (settingsItem == null) {
      return null;
    }
    return settingsItem.getTrackOrdersAfter();
  }

  private boolean isAfterCutoff(FetchTcgClient.SellerOffer offer, Instant cutoff) {
    if (offer.acceptedAt() == null) {
      LOGGER.warn("offer {} has null acceptedAt, skipping (fail-closed)", offer.id());
      return false;
    }
    try {
      var acceptedInstant = ACCEPTED_AT_FORMATTER.parse(offer.acceptedAt(), Instant::from);
      return acceptedInstant.isAfter(cutoff);
    } catch (Exception e) {
      throw new RuntimeException(
          "offer " + offer.id() + " has unparseable acceptedAt '" + offer.acceptedAt() + "'", e);
    }
  }

  private void releaseVoidingOrder(String user, OrderItem order) {
    for (var line : order.getLines()) {
      for (var sequenceNumber : line.getAllocatedSequenceNumbers()) {
        inventoryRepository.updateUnitForRelease(
            user, order.getOrderId(), line.getSkuId(), sequenceNumber);
      }
    }
    orderRepository.finishVoiding(user, order.getOrderId());
  }

  private List<OrderItem> loadExistingOrders(String user) {
    var results = new ArrayList<OrderItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(SkuItem.formatUserPk(user))
                        .sortValue(OrderItem.ORDER_PREFIX)
                        .build()))
            .consistentRead(true)
            .build();

    orderTable.query(request).items().forEach(results::add);
    return results;
  }

  private Map<Integer, String> buildListingToSkuMap(String user) {
    var map = new HashMap<Integer, String>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(SkuItem.formatGsi2pk(user)).build()))
            .build();

    skuTable.index(TcgInventoryTable.GSI2_NAME).query(request).stream()
        .flatMap(page -> page.items().stream())
        .forEach(
            item -> {
              Games.get(item.getGame());
              if (item.getFetchtcgListingId() != null) {
                map.put(item.getFetchtcgListingId(), item.getSkuId());
              }
            });
    return map;
  }

  private List<PlannedReservation> planReservations(
      String user,
      List<FetchTcgClient.SellerOffer> newOffers,
      Map<Integer, String> listingToSkuId) {
    var requestedBySku = new LinkedHashMap<String, Integer>();
    for (var offer : newOffers) {
      if (offer.items() == null || offer.items().isEmpty()) {
        throw new ReservationPreflightException(
            "Offer " + offer.id() + " has no card lines; check FetchTCG");
      }
      for (var item : offer.items()) {
        if (item.listing() == null) {
          throw new ReservationPreflightException(
              "Offer " + offer.id() + " contains a line without a listing; check FetchTCG");
        }
        var listingId = item.listing().id();
        var skuId = listingToSkuId.get(listingId);
        if (skuId == null) {
          throw new ReservationPreflightException(
              "Offer "
                  + offer.id()
                  + " contains listing "
                  + listingId
                  + " with no matching inventory SKU");
        }
        requestedBySku.merge(skuId, item.quantity(), Integer::sum);
      }
    }

    var unitsBySku = new HashMap<String, List<UnitItem>>();
    for (var entry : requestedBySku.entrySet()) {
      unitsBySku.put(
          entry.getKey(),
          inventoryRepository.findUnitsToAllocate(user, entry.getKey(), entry.getValue()));
    }

    var nextBySku = new HashMap<String, Integer>();
    var plans = new ArrayList<PlannedReservation>();
    for (var offer : newOffers) {
      var lines = new ArrayList<OrderItem.OrderLine>();
      for (var item : offer.items()) {
        var listingId = item.listing().id();
        var skuId = listingToSkuId.get(listingId);
        var selected = unitsBySku.get(skuId);
        var start = nextBySku.getOrDefault(skuId, 0);
        var end = start + item.quantity();
        if (end > selected.size()) {
          throw new ReservationPreflightException(
              "Offer "
                  + offer.id()
                  + " needs "
                  + item.quantity()
                  + " units from listing "
                  + listingId
                  + " (SKU "
                  + skuId
                  + "); only "
                  + Math.max(0, selected.size() - start)
                  + " remain in stock");
        }
        var allocated =
            selected.subList(start, end).stream().map(UnitItem::getSequenceNumber).toList();
        nextBySku.put(skuId, end);
        lines.add(
            new OrderItem.OrderLine(
                skuId,
                listingId,
                item.quantity(),
                item.price() != null ? item.price().toPlainString() : null,
                item.listing().listedPrice() != null
                    ? item.listing().listedPrice().toPlainString()
                    : null,
                allocated));
      }
      plans.add(new PlannedReservation(offer, List.copyOf(lines)));
    }
    return List.copyOf(plans);
  }

  private void resumeReservingOrder(String user, OrderItem order) {
    if (!"reserving".equals(order.getStatus())) {
      throw new IllegalStateException("order is not reserving");
    }
    var allocated = allocatedUnits(order);
    if (allocated.size() != new HashSet<>(allocated).size()) {
      throw new IllegalStateException("order allocation contains a duplicate unit");
    }
    for (var unitKey : allocated) {
      inventoryRepository.updateUnitForReservation(
          user, order.getOrderId(), unitKey.skuId(), unitKey.sequenceNumber());
    }

    var action = order.getFetchtcgCurrentAction();
    var targetState =
        action != null && PAYMENT_ACTIONS.contains(action) ? "to_pick" : "awaiting_payment";
    orderRepository.finishReservation(user, order.getOrderId(), targetState);
  }

  private List<AllocatedUnit> allocatedUnits(OrderItem order) {
    var result = new ArrayList<AllocatedUnit>();
    for (var line : order.getLines()) {
      for (var sequenceNumber : line.getAllocatedSequenceNumbers()) {
        result.add(new AllocatedUnit(line.getSkuId(), sequenceNumber));
      }
    }
    return result;
  }

  private static Fulfillment toFulfillment(FetchTcgClient.SellerOffer offer) {
    return new Fulfillment(
        blankToNull(offer.buyerName()),
        toBuyerAddress(offer.buyerRegionAddress()),
        offer.shippingOption() != null ? blankToNull(offer.shippingOption().title()) : null);
  }

  @Nullable
  private static OrderItem.BuyerAddress toBuyerAddress(
      @Nullable FetchTcgClient.BuyerRegionAddress address) {
    if (address == null) {
      return null;
    }

    var line1 = blankToNull(address.line1());
    var line2 = blankToNull(address.line2());
    var suburb = blankToNull(address.suburb());
    var city = blankToNull(address.city());
    var postCode = blankToNull(address.postCode());
    var country = blankToNull(address.country());

    // FetchTCG returns the address object with every part null until the buyer supplies one
    if (line1 == null
        && line2 == null
        && suburb == null
        && city == null
        && postCode == null
        && country == null) {
      return null;
    }

    return OrderItem.BuyerAddress.create(line1, line2, suburb, city, postCode, country);
  }

  @Nullable
  private static String blankToNull(@Nullable String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
