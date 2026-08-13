package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

public class OrderPhaseProcessor {
  private static final Set<String> PAYMENT_ACTIONS =
      Set.of(
          "SEND_PICKUP_ADDRESS",
          "SEND_TRACKING",
          "SEND_REVIEW",
          "AWAIT_REVIEW",
          "SEND_TRACKING_PICKUP");

  private static final Set<String> ACTIVE_OFFER_STATUSES = Set.of("ACCEPTED", "COMPLETED");

  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;
  private final UlidGenerator ulidGenerator;
  private final FetchTcgClient fetchTcgClient;
  private final ObjectMapper objectMapper;

  public OrderPhaseProcessor(
      DynamoDbTable<TcgInventoryItem> tcgInventoryTable,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      UlidGenerator ulidGenerator,
      FetchTcgClient fetchTcgClient,
      ObjectMapper objectMapper) {
    this.tcgInventoryTable = tcgInventoryTable;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
    this.ulidGenerator = ulidGenerator;
    this.fetchTcgClient = fetchTcgClient;
    this.objectMapper = objectMapper;
  }

  public void process(String user, String bearerToken) {
    var allOffers = paginateOffers(bearerToken);
    var offerMap =
        allOffers.stream().collect(Collectors.toMap(o -> String.valueOf(o.id()), o -> o));

    var existingOrders = loadExistingOrders(user);

    var listingToSkuId = buildListingToSkuMap(user);

    for (var order : existingOrders) {
      if (!"awaiting_payment".equals(order.getStatus())) {
        continue;
      }

      var offerId = order.getOrderId();
      var offer = offerMap.get(offerId);
      var offerStillActive = offer != null && ACTIVE_OFFER_STATUSES.contains(offer.status());
      var paymentReceived =
          offer != null
              && offer.currentAction() != null
              && PAYMENT_ACTIONS.contains(offer.currentAction());

      if (!offerStillActive) {
        voidOrder(user, order);
      } else if (paymentReceived) {
        advanceToPickReady(order, offer);
      }
    }

    var existingOrderIds =
        existingOrders.stream().map(TcgInventoryItem::getOrderId).collect(Collectors.toSet());

    for (var offer : allOffers) {
      var offerId = String.valueOf(offer.id());
      if (existingOrderIds.contains(offerId)) {
        continue;
      }

      if ("ACCEPTED".equals(offer.status())) {
        reserveForNewOffer(user, offer, listingToSkuId);
      }
    }
  }

  private List<FetchTcgClient.SellerOffer> paginateOffers(String bearerToken) {
    var allOffers = new ArrayList<FetchTcgClient.SellerOffer>();
    int page = 1;
    while (true) {
      var response = fetchTcgClient.getSellerOffers(bearerToken, page);
      allOffers.addAll(response.content());
      if (page >= response.totalPages()) {
        break;
      }
      page++;
    }
    return allOffers;
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
    var orderLines = new ArrayList<OrderLine>();
    var transactItems = new ArrayList<TransactWriteItem>();
    var affectedSkuIds = new HashSet<String>();
    boolean insufficientStock = false;

    if (offer.items() != null) {
      for (var item : offer.items()) {
        var skuId = listingToSkuId.get(item.listing().id());
        if (skuId == null) {
          insufficientStock = true;
          continue;
        }

        var units = findInStockUnits(user, skuId, item.quantity());
        if (units.size() < item.quantity()) {
          insufficientStock = true;
        }

        var allocatedSequenceNumbers = new ArrayList<Integer>();
        for (var unit : units) {
          allocatedSequenceNumbers.add(unit.getSequenceNumber());
          transactItems.add(buildUnitReserveUpdate(user, skuId, unit.getSequenceNumber(), offerId));
        }

        if (!affectedSkuIds.contains(skuId)) {
          transactItems.add(buildSkuDirtyUpdate(user, skuId));
          affectedSkuIds.add(skuId);
        }

        orderLines.add(
            new OrderLine(
                skuId,
                item.listing().id(),
                item.quantity(),
                item.price() != null ? item.price().toPlainString() : null,
                allocatedSequenceNumbers));
      }
    }

    var orderItem = new TcgInventoryItem();
    orderItem.setPk(TcgInventoryItem.formatUserPk(user));
    orderItem.setSk(TcgInventoryItem.formatOrderSk(offerId));
    orderItem.setOrderId(offerId);
    orderItem.setStatus(insufficientStock ? "flagged" : "awaiting_payment");
    orderItem.setFetchtcgStatus(offer.status());
    orderItem.setFetchtcgCurrentAction(offer.currentAction());
    orderItem.setDeliveryMode(offer.deliveryMode());
    orderItem.setTotalPrice(
        offer.totalOfferPrice() != null ? offer.totalOfferPrice().toPlainString() : null);
    orderItem.setCreatedAt(clock.now());
    orderItem.setUpdatedAt(clock.now());

    try {
      orderItem.setLines(objectMapper.writeValueAsString(orderLines));
    } catch (Exception e) {
      throw new RuntimeException("failed to serialize order lines", e);
    }

    var orderPut =
        TransactWriteItem.builder()
            .put(
                Put.builder()
                    .tableName(TcgInventoryItem.TABLE_NAME)
                    .item(itemToAttributeMap(orderItem))
                    .conditionExpression("attribute_not_exists(pk)")
                    .build())
            .build();
    transactItems.add(orderPut);

    var auditItem = new HashMap<String, AttributeValue>();
    auditItem.put(
        TcgInventoryItem.PK,
        AttributeValue.builder().s(TcgInventoryItem.formatAuditPk(user)).build());
    auditItem.put(
        TcgInventoryItem.SK, AttributeValue.builder().s(ulidGenerator.generate()).build());
    auditItem.put(TcgInventoryItem.EVENT_TYPE, AttributeValue.builder().s("reserve").build());
    auditItem.put(TcgInventoryItem.ORDER_ID, AttributeValue.builder().s(offerId).build());
    auditItem.put(
        TcgInventoryItem.CREATED_AT,
        AttributeValue.builder().n(String.valueOf(clock.now().getEpochSecond())).build());
    transactItems.add(
        TransactWriteItem.builder()
            .put(Put.builder().tableName(TcgInventoryItem.TABLE_NAME).item(auditItem).build())
            .build());

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder().transactItems(transactItems).build());
  }

  private void voidOrder(String user, TcgInventoryItem order) {
    var transactItems = new ArrayList<TransactWriteItem>();
    var affectedSkuIds = new HashSet<String>();
    var offerId = order.getOrderId();

    List<OrderLine> orderLines = parseOrderLines(order.getLines());
    for (var line : orderLines) {
      for (var seqNum : line.allocatedSequenceNumbers()) {
        transactItems.add(buildUnitReleaseUpdate(user, line.skuId(), seqNum));
      }
      if (!affectedSkuIds.contains(line.skuId())) {
        transactItems.add(buildSkuDirtyUpdate(user, line.skuId()));
        affectedSkuIds.add(line.skuId());
      }
    }

    order.setStatus("voided");
    order.setUpdatedAt(clock.now());
    var orderPut =
        TransactWriteItem.builder()
            .put(
                Put.builder()
                    .tableName(TcgInventoryItem.TABLE_NAME)
                    .item(itemToAttributeMap(order))
                    .build())
            .build();
    transactItems.add(orderPut);

    var auditItem = new HashMap<String, AttributeValue>();
    auditItem.put(
        TcgInventoryItem.PK,
        AttributeValue.builder().s(TcgInventoryItem.formatAuditPk(user)).build());
    auditItem.put(
        TcgInventoryItem.SK, AttributeValue.builder().s(ulidGenerator.generate()).build());
    auditItem.put(TcgInventoryItem.EVENT_TYPE, AttributeValue.builder().s("release").build());
    auditItem.put(TcgInventoryItem.ORDER_ID, AttributeValue.builder().s(offerId).build());
    auditItem.put(
        TcgInventoryItem.CREATED_AT,
        AttributeValue.builder().n(String.valueOf(clock.now().getEpochSecond())).build());
    transactItems.add(
        TransactWriteItem.builder()
            .put(Put.builder().tableName(TcgInventoryItem.TABLE_NAME).item(auditItem).build())
            .build());

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder().transactItems(transactItems).build());
  }

  private void advanceToPickReady(TcgInventoryItem order, FetchTcgClient.SellerOffer offer) {
    order.setStatus("to_pick");
    order.setFetchtcgStatus(offer.status());
    order.setFetchtcgCurrentAction(offer.currentAction());
    order.setUpdatedAt(clock.now());
    tcgInventoryTable.putItem(order);
  }

  private List<TcgInventoryItem> findInStockUnits(String user, String skuId, int quantity) {
    var results = new ArrayList<TcgInventoryItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(TcgInventoryItem.formatSkuPk(user, skuId))
                        .sortValue(TcgInventoryItem.UNIT_PREFIX)
                        .build()))
            .build();

    for (var item : tcgInventoryTable.query(request).items()) {
      if ("in_stock".equals(item.getStatus())) {
        results.add(item);
        if (results.size() >= quantity) {
          break;
        }
      }
    }
    return results;
  }

  private TransactWriteItem buildUnitReserveUpdate(
      String user, String skuId, int sequenceNumber, String orderId) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var unitSk = TcgInventoryItem.formatUnitSk(sequenceNumber);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                        TcgInventoryItem.SK, AttributeValue.builder().s(unitSk).build()))
                .updateExpression(
                    "SET #status = :reserved, "
                        + TcgInventoryItem.ORDER_ID
                        + " = :orderId, "
                        + TcgInventoryItem.UPDATED_AT
                        + " = :now")
                .conditionExpression("#status = :inStock")
                .expressionAttributeNames(Map.of("#status", TcgInventoryItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":reserved", AttributeValue.builder().s("reserved").build(),
                        ":inStock", AttributeValue.builder().s("in_stock").build(),
                        ":orderId", AttributeValue.builder().s(orderId).build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  private TransactWriteItem buildUnitReleaseUpdate(String user, String skuId, int sequenceNumber) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var unitSk = TcgInventoryItem.formatUnitSk(sequenceNumber);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                        TcgInventoryItem.SK, AttributeValue.builder().s(unitSk).build()))
                .updateExpression(
                    "SET #status = :inStock, "
                        + TcgInventoryItem.UPDATED_AT
                        + " = :now REMOVE "
                        + TcgInventoryItem.ORDER_ID)
                .conditionExpression("#status = :reserved")
                .expressionAttributeNames(Map.of("#status", TcgInventoryItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":inStock", AttributeValue.builder().s("in_stock").build(),
                        ":reserved", AttributeValue.builder().s("reserved").build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  private TransactWriteItem buildSkuDirtyUpdate(String user, String skuId) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                        TcgInventoryItem.SK,
                            AttributeValue.builder().s(TcgInventoryItem.formatSkuSk()).build()))
                .updateExpression(
                    "ADD "
                        + TcgInventoryItem.VERSION
                        + " :one SET "
                        + TcgInventoryItem.DIRTY
                        + " = :dirty, "
                        + TcgInventoryItem.GSI1PK
                        + " = :gsi1pk")
                .expressionAttributeValues(
                    Map.of(
                        ":one", AttributeValue.builder().n("1").build(),
                        ":dirty", AttributeValue.builder().bool(true).build(),
                        ":gsi1pk",
                            AttributeValue.builder()
                                .s(TcgInventoryItem.formatGsi1pk(user))
                                .build()))
                .build())
        .build();
  }

  private Map<String, AttributeValue> itemToAttributeMap(TcgInventoryItem item) {
    var map = new HashMap<String, AttributeValue>();
    map.put(TcgInventoryItem.PK, AttributeValue.builder().s(item.getPk()).build());
    map.put(TcgInventoryItem.SK, AttributeValue.builder().s(item.getSk()).build());
    if (item.getOrderId() != null) {
      map.put(TcgInventoryItem.ORDER_ID, AttributeValue.builder().s(item.getOrderId()).build());
    }
    if (item.getStatus() != null) {
      map.put(TcgInventoryItem.STATUS, AttributeValue.builder().s(item.getStatus()).build());
    }
    if (item.getFetchtcgStatus() != null) {
      map.put(
          TcgInventoryItem.FETCHTCG_STATUS,
          AttributeValue.builder().s(item.getFetchtcgStatus()).build());
    }
    if (item.getFetchtcgCurrentAction() != null) {
      map.put(
          TcgInventoryItem.FETCHTCG_CURRENT_ACTION,
          AttributeValue.builder().s(item.getFetchtcgCurrentAction()).build());
    }
    if (item.getDeliveryMode() != null) {
      map.put(
          TcgInventoryItem.DELIVERY_MODE,
          AttributeValue.builder().s(item.getDeliveryMode()).build());
    }
    if (item.getTotalPrice() != null) {
      map.put(
          TcgInventoryItem.TOTAL_PRICE, AttributeValue.builder().s(item.getTotalPrice()).build());
    }
    if (item.getLines() != null) {
      map.put(TcgInventoryItem.LINES, AttributeValue.builder().s(item.getLines()).build());
    }
    if (item.getCreatedAt() != null) {
      map.put(
          TcgInventoryItem.CREATED_AT,
          AttributeValue.builder().n(String.valueOf(item.getCreatedAt().getEpochSecond())).build());
    }
    if (item.getUpdatedAt() != null) {
      map.put(
          TcgInventoryItem.UPDATED_AT,
          AttributeValue.builder().n(String.valueOf(item.getUpdatedAt().getEpochSecond())).build());
    }
    return map;
  }

  private List<OrderLine> parseOrderLines(String linesJson) {
    if (linesJson == null || linesJson.isEmpty()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(linesJson, new TypeReference<List<OrderLine>>() {});
    } catch (Exception e) {
      throw new RuntimeException("failed to parse order lines", e);
    }
  }

  record OrderLine(
      @JsonProperty("sku_id") String skuId,
      @JsonProperty("fetchtcg_listing_id") int fetchtcgListingId,
      @JsonProperty("quantity") int quantity,
      @JsonProperty("price") String price,
      @JsonProperty("allocated_sequence_numbers") List<Integer> allocatedSequenceNumbers) {}
}
