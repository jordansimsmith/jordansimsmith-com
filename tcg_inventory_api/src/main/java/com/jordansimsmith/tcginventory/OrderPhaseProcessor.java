package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.databind.ObjectMapper;
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

  private static final DateTimeFormatter ACCEPTED_AT_FORMATTER =
      new DateTimeFormatterBuilder()
          .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
          .optionalStart()
          .appendFraction(ChronoField.MILLI_OF_SECOND, 0, 3, true)
          .optionalEnd()
          .appendOffset("+HHmm", "Z")
          .toFormatter();

  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final TcgInventoryItemRepository tcgInventoryItemRepository;
  private final Clock clock;
  private final FetchTcgClient fetchTcgClient;
  private final ObjectMapper objectMapper;

  public OrderPhaseProcessor(
      DynamoDbTable<TcgInventoryItem> tcgInventoryTable,
      TcgInventoryItemRepository tcgInventoryItemRepository,
      Clock clock,
      FetchTcgClient fetchTcgClient,
      ObjectMapper objectMapper) {
    this.tcgInventoryTable = tcgInventoryTable;
    this.tcgInventoryItemRepository = tcgInventoryItemRepository;
    this.clock = clock;
    this.fetchTcgClient = fetchTcgClient;
    this.objectMapper = objectMapper;
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
    for (var order : existingOrders) {
      if (!"awaiting_payment".equals(order.getStatus())) {
        continue;
      }

      var offerId = order.getOrderId();
      var offer = offerMap.get(offerId);
      var paymentReceived =
          offer != null
              && offer.currentAction() != null
              && PAYMENT_ACTIONS.contains(offer.currentAction());

      if (paymentReceived) {
        tcgInventoryItemRepository.advanceOrderToPickReady(
            user, order.getOrderId(), offer.status(), offer.currentAction());
        advancedCount++;
      }
    }
    LOGGER.info("advanced {} orders to pick-ready", advancedCount);

    var existingOrderIds =
        existingOrders.stream().map(TcgInventoryItem::getOrderId).collect(Collectors.toSet());

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
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatSettingsSk())
            .build();
    var settingsItem = tcgInventoryTable.getItem(key);
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

  private List<TcgInventoryItem> loadExistingOrders(String user) {
    var results = new ArrayList<TcgInventoryItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(TcgInventoryItem.formatUserPk(user))
                        .sortValue(TcgInventoryItem.ORDER_PREFIX)
                        .build()))
            .build();

    tcgInventoryTable.query(request).items().forEach(results::add);
    return results;
  }

  private Map<Integer, String> buildListingToSkuMap(String user) {
    var map = new HashMap<Integer, String>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(TcgInventoryItem.formatGsi2pk(user))
                        .sortValue(TcgInventoryItem.NAME_PREFIX)
                        .build()))
            .build();

    tcgInventoryTable.index(TcgInventoryItem.GSI2_NAME).query(request).stream()
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
    var orderLines = new ArrayList<OrderLines.OrderLine>();
    var newReservations = new LinkedHashMap<String, List<Integer>>();
    boolean insufficientStock = false;

    if (offer.items() != null) {
      for (var item : offer.items()) {
        var skuId = listingToSkuId.get(item.listing().id());
        if (skuId == null) {
          insufficientStock = true;
          continue;
        }

        var units =
            tcgInventoryItemRepository.findUnitsToAllocate(user, skuId, offerId, item.quantity());
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
            new OrderLines.OrderLine(
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

    String linesJson;
    try {
      linesJson = objectMapper.writeValueAsString(orderLines);
    } catch (Exception e) {
      throw new RuntimeException("failed to serialize order lines", e);
    }

    var orderItem =
        TcgInventoryItem.createOrder(
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
            offer.totalOfferPrice() != null ? offer.totalOfferPrice().toPlainString() : null,
            linesJson,
            clock.now());

    var skuUnits =
        newReservations.entrySet().stream()
            .map(entry -> new TcgInventoryItemRepository.SkuUnits(entry.getKey(), entry.getValue()))
            .toList();
    tcgInventoryItemRepository.reserveOrder(user, orderItem, skuUnits);
  }
}
