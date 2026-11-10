package com.jordansimsmith.tcginventory.orders.server;

import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.fetchtcg.client.BuyerAddress;
import com.jordansimsmith.tcginventory.fetchtcg.client.FetchTcgService;
import com.jordansimsmith.tcginventory.fetchtcg.client.FindOffersRequest;
import com.jordansimsmith.tcginventory.fetchtcg.client.Offer;
import com.jordansimsmith.tcginventory.inventory.client.InventoryService;
import com.jordansimsmith.tcginventory.inventory.client.ReleaseUnitRequest;
import com.jordansimsmith.tcginventory.inventory.client.ReserveUnitRequest;
import com.jordansimsmith.tcginventory.listings.client.FindListingsRequest;
import com.jordansimsmith.tcginventory.listings.client.ListingService;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrdersRequest;
import com.jordansimsmith.tcginventory.orders.client.UpdateOrdersResponse;
import com.jordansimsmith.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateOrdersService {
  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateOrdersService.class);

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

  private record PreparedOrder(Offer offer, List<OrderRowItem> rows) {}

  private final ListingService listingService;
  private final OrderReadRepository orderReadRepository;
  private final OrderMutationRepository orderMutationRepository;
  private final OrderSettingsRepository orderSettingsRepository;
  private final InventoryService inventoryService;
  private final Clock clock;
  private final FetchTcgService fetchTcgService;

  public UpdateOrdersService(
      OrderReadRepository orderReadRepository,
      OrderMutationRepository orderMutationRepository,
      OrderSettingsRepository orderSettingsRepository,
      FetchTcgService fetchTcgService,
      ListingService listingService,
      InventoryService inventoryService,
      Clock clock) {
    this.orderReadRepository = orderReadRepository;
    this.orderMutationRepository = orderMutationRepository;
    this.orderSettingsRepository = orderSettingsRepository;
    this.fetchTcgService = fetchTcgService;
    this.listingService = listingService;
    this.inventoryService = inventoryService;
    this.clock = clock;
  }

  public UpdateOrdersResponse updateOrders(RequestContext ctx, UpdateOrdersRequest request)
      throws ServiceException {
    var user = ctx.user();
    var allOffers = paginateOffers(ctx);
    LOGGER.info("fetched {} offers from FetchTCG for user {}", allOffers.size(), user);

    if (!allOffers.isEmpty()) {
      var statusCounts =
          allOffers.stream().collect(Collectors.groupingBy(o -> o.status(), Collectors.counting()));
      LOGGER.info("offer statuses: {}", statusCounts);
    }

    var orderSettings = orderSettingsRepository.get(user);
    var trackOrdersAfter = orderSettings == null ? null : orderSettings.getTrackOrdersAfter();
    if (trackOrdersAfter != null) {
      LOGGER.info("track_orders_after is set to {}", trackOrdersAfter);
    }

    var offerMap =
        allOffers.stream().collect(Collectors.toMap(o -> String.valueOf(o.id()), o -> o));

    var existingOrders = orderReadRepository.findAllOrders(user);
    LOGGER.info("found {} existing orders in DynamoDB", existingOrders.size());

    var existingOrderIds =
        existingOrders.stream().map(OrderItem::getOrderId).collect(Collectors.toSet());
    var newOffers =
        allOffers.stream()
            .filter(offer -> "ACCEPTED".equals(offer.status()))
            .filter(offer -> trackOrdersAfter == null || isAfterCutoff(offer, trackOrdersAfter))
            .filter(offer -> !existingOrderIds.contains(String.valueOf(offer.id())))
            .toList();

    var listingToSkuId = buildListingToSkuMap(ctx, newOffers);
    LOGGER.info("built listing-to-sku map with {} entries", listingToSkuId.size());

    var preparedOrders = prepareOrders(user, newOffers, listingToSkuId);

    int advancedCount = 0;
    int voidedCount = 0;
    int refreshedCount = 0;
    for (var order : existingOrders) {
      var offer = offerMap.get(order.getOrderId());
      if (offer == null) {
        throw new IllegalStateException("FetchTCG offer not found for order " + order.getOrderId());
      }

      var cancelled = offer.status() != null && CANCELLED_STATUSES.contains(offer.status());
      var paymentReceived =
          offer.currentAction() != null && PAYMENT_ACTIONS.contains(offer.currentAction());
      if ("reserving".equals(order.getStatus())) {
        reserveOrderUnits(ctx, order);
      }
      if ("voiding".equals(order.getStatus())) {
        releaseVoidingOrder(ctx, order);
        voidedCount++;
      } else if ("awaiting_payment".equals(order.getStatus())) {
        if (cancelled) {
          orderMutationRepository.startVoiding(user, order, offer.status());
          releaseVoidingOrder(ctx, order);
          voidedCount++;
        } else if (paymentReceived) {
          orderMutationRepository.advanceOrderToPickReady(
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
        orderMutationRepository.updateOrderFulfillment(
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

    for (var preparedOrder : preparedOrders) {
      var offer = preparedOrder.offer();
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
              preparedOrder.rows().size(),
              clock.now());
      orderMutationRepository.createReservingOrder(order, preparedOrder.rows());
      reserveOrderUnits(ctx, order);
    }
    LOGGER.info("created {} new orders", preparedOrders.size());
    return new UpdateOrdersResponse();
  }

  private List<Offer> paginateOffers(RequestContext ctx) throws ServiceException {
    var allOffers = new ArrayList<Offer>();
    String continuation = null;
    do {
      var response = fetchTcgService.findOffers(ctx, new FindOffersRequest(100, continuation));
      allOffers.addAll(response.offers());
      continuation = response.nextContinuation();
    } while (continuation != null);
    return allOffers;
  }

  private boolean isAfterCutoff(Offer offer, Instant cutoff) {
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

  private void releaseVoidingOrder(RequestContext ctx, OrderItem order) throws ServiceException {
    var user = ctx.user();
    for (var line : orderReadRepository.findRows(user, order)) {
      for (var sequenceNumber : line.getAllocatedSequenceNumbers()) {
        inventoryService.releaseUnit(
            ctx, new ReleaseUnitRequest(line.getSkuId(), sequenceNumber, order.getOrderId()));
      }
    }
    orderMutationRepository.finishVoiding(user, order.getOrderId());
  }

  private Map<Integer, String> buildListingToSkuMap(RequestContext ctx, List<Offer> offers)
      throws ServiceException {
    var ids = new LinkedHashSet<Integer>();
    for (var offer : offers) {
      if (offer.items() == null || offer.items().isEmpty()) {
        throw new ConflictException("Offer " + offer.id() + " has no card lines; check FetchTCG");
      }
      for (var item : offer.items()) {
        if (item.listing() == null) {
          throw new ConflictException(
              "Offer " + offer.id() + " contains a line without a listing; check FetchTCG");
        }
        ids.add(item.listing().id());
      }
    }
    var listingIds = new ArrayList<>(ids);
    var map = new HashMap<Integer, String>();
    for (int start = 0; start < listingIds.size(); start += 100) {
      var response =
          listingService.findListings(
              ctx,
              new FindListingsRequest(
                  listingIds.subList(start, Math.min(start + 100, listingIds.size()))));
      for (var listing : response.listings()) {
        map.put(listing.listingId(), listing.skuId());
      }
    }
    return map;
  }

  private List<PreparedOrder> prepareOrders(
      String user, List<Offer> newOffers, Map<Integer, String> listingToSkuId)
      throws ServiceException {
    var preparedOrders = new ArrayList<PreparedOrder>();
    for (var offer : newOffers) {
      var lines = new ArrayList<OrderRowItem>();
      for (var item : offer.items()) {
        var listingId = item.listing().id();
        var skuId = listingToSkuId.get(listingId);
        if (skuId == null) {
          throw new ConflictException(
              "Offer "
                  + offer.id()
                  + " contains listing "
                  + listingId
                  + " with no matching inventory SKU");
        }
        if (item.quantity() <= 0) {
          throw new ConflictException("Offer " + offer.id() + " contains an invalid quantity");
        }
        lines.add(
            OrderRowItem.create(
                user,
                String.valueOf(offer.id()),
                lines.size(),
                skuId,
                listingId,
                item.quantity(),
                item.price() != null ? item.price().toPlainString() : null,
                item.listing().listedPrice() != null
                    ? item.listing().listedPrice().toPlainString()
                    : null,
                new ArrayList<>()));
      }
      preparedOrders.add(new PreparedOrder(offer, List.copyOf(lines)));
    }
    return List.copyOf(preparedOrders);
  }

  private void reserveOrderUnits(RequestContext ctx, OrderItem order) throws ServiceException {
    var user = ctx.user();
    if (!"reserving".equals(order.getStatus())) {
      throw new IllegalStateException("order is not reserving");
    }
    var rows = orderReadRepository.findRows(user, order);
    var allocated = allocatedUnits(rows);
    if (allocated.size() != new HashSet<>(allocated).size()) {
      throw new IllegalStateException("order allocation contains a duplicate unit");
    }
    for (var line : rows) {
      for (int unitIndex = line.getAllocatedSequenceNumbers().size();
          unitIndex < line.getQuantity();
          unitIndex++) {
        var operationId =
            "order:" + order.getOrderId() + ":line:" + line.getPosition() + ":unit:" + unitIndex;
        var result =
            inventoryService.reserveUnit(
                ctx, new ReserveUnitRequest(operationId, order.getOrderId(), line.getSkuId()));
        orderMutationRepository.recordReservation(
            user, order.getOrderId(), line.getPosition(), unitIndex, result.sequenceNumber());
        line.getAllocatedSequenceNumbers().add(result.sequenceNumber());
      }
    }

    var action = order.getFetchtcgCurrentAction();
    var targetState =
        action != null && PAYMENT_ACTIONS.contains(action) ? "to_pick" : "awaiting_payment";
    orderMutationRepository.finishReservation(user, order.getOrderId(), targetState);
    order.setStatus(targetState);
  }

  private List<AllocatedUnit> allocatedUnits(List<OrderRowItem> rows) {
    var result = new ArrayList<AllocatedUnit>();
    for (var line : rows) {
      for (var sequenceNumber : line.getAllocatedSequenceNumbers()) {
        result.add(new AllocatedUnit(line.getSkuId(), sequenceNumber));
      }
    }
    return result;
  }

  private static Fulfillment toFulfillment(Offer offer) {
    return new Fulfillment(
        blankToNull(offer.buyerName()),
        toBuyerAddress(offer.buyerAddress()),
        offer.shippingOption() != null ? blankToNull(offer.shippingOption().title()) : null);
  }

  @Nullable
  private static OrderItem.BuyerAddress toBuyerAddress(@Nullable BuyerAddress address) {
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
