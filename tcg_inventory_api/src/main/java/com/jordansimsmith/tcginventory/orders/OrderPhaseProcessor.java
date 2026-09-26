package com.jordansimsmith.tcginventory.orders;

import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgClient;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.settings.SettingsItem;
import com.jordansimsmith.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashMap;
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

  private final DynamoDbTable<OrderItem> orderTable;
  private final DynamoDbTable<SkuItem> skuTable;
  private final DynamoDbTable<SettingsItem> settingsTable;
  private final OrderRepository orderRepository;
  private final Clock clock;
  private final FetchTcgClient fetchTcgClient;

  public OrderPhaseProcessor(
      DynamoDbTable<OrderItem> orderTable,
      DynamoDbTable<SkuItem> skuTable,
      DynamoDbTable<SettingsItem> settingsTable,
      OrderRepository orderRepository,
      Clock clock,
      FetchTcgClient fetchTcgClient) {
    this.orderTable = orderTable;
    this.skuTable = skuTable;
    this.settingsTable = settingsTable;
    this.orderRepository = orderRepository;
    this.clock = clock;
    this.fetchTcgClient = fetchTcgClient;
  }

  public void process(String user, String bearerToken) {
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
          releaseCancelledOrder(user, order, offer);
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

    int createdCount = 0;
    int skippedCount = 0;
    int cutoffSkippedCount = 0;
    for (var offer : allOffers) {
      var offerId = String.valueOf(offer.id());
      if (existingOrderIds.contains(offerId)) {
        skippedCount++;
        continue;
      }

      if ("ACCEPTED".equals(offer.status())) {
        if (trackOrdersAfter != null && !isAfterCutoff(offer, trackOrdersAfter)) {
          cutoffSkippedCount++;
          continue;
        }
        reserveForNewOffer(user, offer, listingToSkuId);
        createdCount++;
      }
    }
    LOGGER.info(
        "created {} new orders, skipped {} existing, skipped {} before cutoff",
        createdCount,
        skippedCount,
        cutoffSkippedCount);
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
              if (item.getFetchtcgListingId() != null) {
                map.put(item.getFetchtcgListingId(), item.getSkuId());
              }
            });
    return map;
  }

  private void reserveForNewOffer(
      String user, FetchTcgClient.SellerOffer offer, Map<Integer, String> listingToSkuId) {
    var offerId = String.valueOf(offer.id());
    var orderLines = new ArrayList<OrderItem.OrderLine>();
    var newReservations = new LinkedHashMap<String, List<Integer>>();
    boolean insufficientStock = false;

    if (offer.items() != null) {
      for (var item : offer.items()) {
        var skuId = listingToSkuId.get(item.listing().id());
        if (skuId == null) {
          insufficientStock = true;
          continue;
        }

        var units = orderRepository.findUnitsToAllocate(user, skuId, offerId, item.quantity());
        if (units.size() < item.quantity()) {
          insufficientStock = true;
        }

        var allocatedSequenceNumbers = new ArrayList<Integer>();
        for (var unit : units) {
          allocatedSequenceNumbers.add(unit.getSequenceNumber());
          // units already reserved for this offer by a prior partial run need no write
          if ("in_stock".equals(unit.getStatus())) {
            newReservations
                .computeIfAbsent(skuId, k -> new ArrayList<>())
                .add(unit.getSequenceNumber());
          }
        }

        orderLines.add(
            new OrderItem.OrderLine(
                skuId,
                item.listing().id(),
                item.quantity(),
                item.price() != null ? item.price().toPlainString() : null,
                item.listing().listedPrice() != null
                    ? item.listing().listedPrice().toPlainString()
                    : null,
                allocatedSequenceNumbers));
      }
    }

    var fulfillment = toFulfillment(offer);
    var orderItem =
        OrderItem.create(
            user,
            offerId,
            insufficientStock
                ? "flagged"
                : (offer.currentAction() != null && PAYMENT_ACTIONS.contains(offer.currentAction()))
                    ? "to_pick"
                    : "awaiting_payment",
            offer.status(),
            offer.currentAction(),
            offer.deliveryMode(),
            fulfillment.buyerName(),
            fulfillment.buyerAddress(),
            fulfillment.postageOption(),
            offer.totalOfferPrice() != null ? offer.totalOfferPrice().toPlainString() : null,
            orderLines,
            clock.now());

    var skuUnits =
        newReservations.entrySet().stream()
            .map(entry -> new OrderRepository.SkuUnits(entry.getKey(), entry.getValue()))
            .toList();
    orderRepository.reserveOrder(user, orderItem, skuUnits);
  }

  private void releaseCancelledOrder(
      String user, OrderItem order, FetchTcgClient.SellerOffer offer) {
    var releasedUnits = new LinkedHashMap<String, List<Integer>>();
    for (var line : order.getLines()) {
      releasedUnits
          .computeIfAbsent(line.getSkuId(), k -> new ArrayList<>())
          .addAll(line.getAllocatedSequenceNumbers());
    }

    var skuUnits =
        releasedUnits.entrySet().stream()
            .map(entry -> new OrderRepository.SkuUnits(entry.getKey(), entry.getValue()))
            .toList();
    orderRepository.releaseOrder(user, order.getOrderId(), offer.status(), skuUnits);
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
