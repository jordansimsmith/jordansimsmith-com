package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.math.BigDecimal;
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
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

class PublishJobProcessor {
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
  private final FetchTcgTokenMinter fetchTcgTokenMinter;
  private final ObjectMapper objectMapper;

  PublishJobProcessor(
      DynamoDbTable<TcgInventoryItem> tcgInventoryTable,
      DynamoDbClient dynamoDbClient,
      Clock clock,
      UlidGenerator ulidGenerator,
      FetchTcgClient fetchTcgClient,
      FetchTcgTokenMinter fetchTcgTokenMinter,
      ObjectMapper objectMapper) {
    this.tcgInventoryTable = tcgInventoryTable;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
    this.ulidGenerator = ulidGenerator;
    this.fetchTcgClient = fetchTcgClient;
    this.fetchTcgTokenMinter = fetchTcgTokenMinter;
    this.objectMapper = objectMapper;
  }

  BatchResult processBatch(String user, TcgInventoryItem jobItem) {
    var bearerToken = fetchTcgTokenMinter.mint(user);
    var continuation = jobItem.getContinuation() != null ? jobItem.getContinuation() : 0;

    if (continuation == 0) {
      processOrderPhase(user, bearerToken);
    }

    return processPublishPhase(user, bearerToken);
  }

  private void processOrderPhase(String user, String bearerToken) {
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

  private BatchResult processPublishPhase(String user, String bearerToken) {
    var dirtySkus = loadDirtySkus(user);
    int processed = 0;

    for (var sku : dirtySkus) {
      var skuId = sku.getSkuId();
      var capturedVersion = sku.getVersion();
      var inStockCount = countInStockUnits(user, skuId);

      if (inStockCount > 0) {
        publishSku(user, bearerToken, sku, inStockCount, capturedVersion);
      } else if (sku.getFetchtcgListingId() != null) {
        delistSku(user, bearerToken, sku, capturedVersion);
      } else {
        clearDirty(user, skuId, capturedVersion);
      }
      processed++;
    }

    return new BatchResult(processed, true);
  }

  private List<TcgInventoryItem> loadDirtySkus(String user) {
    var results = new ArrayList<TcgInventoryItem>();
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(TcgInventoryItem.formatGsi1pk(user))
                        .sortValue(TcgInventoryItem.SKU_PREFIX)
                        .build()))
            .build();

    tcgInventoryTable.index(TcgInventoryItem.GSI1_NAME).query(request).stream()
        .flatMap(page -> page.items().stream())
        .forEach(results::add);
    return results;
  }

  private int countInStockUnits(String user, String skuId) {
    int count = 0;
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
        count++;
      }
    }
    return count;
  }

  private void publishSku(
      String user,
      String bearerToken,
      TcgInventoryItem sku,
      int inStockCount,
      int capturedVersion) {
    var condition = Condition.valueOf(sku.getCondition()).toFetchtcg();
    var price = new BigDecimal(sku.getSuggestedPrice());

    var upsertRequest =
        new FetchTcgClient.UpsertListingRequest(
            sku.getFetchtcgCardId(), condition, inStockCount, price);

    var response = fetchTcgClient.upsertListing(bearerToken, upsertRequest);

    clearDirtyWithSnapshot(
        user,
        sku.getSkuId(),
        capturedVersion,
        response.listingId(),
        inStockCount,
        price.toPlainString());
  }

  private void delistSku(
      String user, String bearerToken, TcgInventoryItem sku, int capturedVersion) {
    fetchTcgClient.deleteListing(bearerToken, sku.getFetchtcgListingId());
    clearDirtyRemoveSnapshot(user, sku.getSkuId(), capturedVersion);
  }

  private void clearDirtyWithSnapshot(
      String user, String skuId, int capturedVersion, int listingId, int quantity, String price) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var cleanGsi1pk = TcgInventoryItem.USER_PREFIX + user + "#CLEAN";

    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryItem.TABLE_NAME)
              .key(
                  Map.of(
                      TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                      TcgInventoryItem.SK,
                          AttributeValue.builder().s(TcgInventoryItem.formatSkuSk()).build()))
              .updateExpression(
                  "SET "
                      + TcgInventoryItem.DIRTY
                      + " = :clean, "
                      + TcgInventoryItem.GSI1PK
                      + " = :gsi1pk, "
                      + TcgInventoryItem.FETCHTCG_LISTING_ID
                      + " = :listingId, "
                      + TcgInventoryItem.LAST_PUBLISHED_QUANTITY
                      + " = :qty, "
                      + TcgInventoryItem.LAST_PUBLISHED_PRICE
                      + " = :price, "
                      + TcgInventoryItem.LAST_PUBLISHED_AT
                      + " = :now")
              .conditionExpression(
                  TcgInventoryItem.DIRTY
                      + " = :dirty AND "
                      + TcgInventoryItem.VERSION
                      + " = :version")
              .expressionAttributeValues(
                  Map.ofEntries(
                      Map.entry(":clean", AttributeValue.builder().bool(false).build()),
                      Map.entry(":gsi1pk", AttributeValue.builder().s(cleanGsi1pk).build()),
                      Map.entry(
                          ":listingId",
                          AttributeValue.builder().n(String.valueOf(listingId)).build()),
                      Map.entry(
                          ":qty", AttributeValue.builder().n(String.valueOf(quantity)).build()),
                      Map.entry(":price", AttributeValue.builder().s(price).build()),
                      Map.entry(
                          ":now",
                          AttributeValue.builder()
                              .n(String.valueOf(clock.now().getEpochSecond()))
                              .build()),
                      Map.entry(":dirty", AttributeValue.builder().bool(true).build()),
                      Map.entry(
                          ":version",
                          AttributeValue.builder().n(String.valueOf(capturedVersion)).build())))
              .build());
    } catch (ConditionalCheckFailedException e) {
      // version mismatch — SKU stays dirty for the next run
    }
  }

  private void clearDirtyRemoveSnapshot(String user, String skuId, int capturedVersion) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var cleanGsi1pk = TcgInventoryItem.USER_PREFIX + user + "#CLEAN";

    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryItem.TABLE_NAME)
              .key(
                  Map.of(
                      TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                      TcgInventoryItem.SK,
                          AttributeValue.builder().s(TcgInventoryItem.formatSkuSk()).build()))
              .updateExpression(
                  "SET "
                      + TcgInventoryItem.DIRTY
                      + " = :clean, "
                      + TcgInventoryItem.GSI1PK
                      + " = :gsi1pk REMOVE "
                      + TcgInventoryItem.FETCHTCG_LISTING_ID
                      + ", "
                      + TcgInventoryItem.LAST_PUBLISHED_QUANTITY
                      + ", "
                      + TcgInventoryItem.LAST_PUBLISHED_PRICE
                      + ", "
                      + TcgInventoryItem.LAST_PUBLISHED_AT)
              .conditionExpression(
                  TcgInventoryItem.DIRTY
                      + " = :dirty AND "
                      + TcgInventoryItem.VERSION
                      + " = :version")
              .expressionAttributeValues(
                  Map.of(
                      ":clean", AttributeValue.builder().bool(false).build(),
                      ":gsi1pk", AttributeValue.builder().s(cleanGsi1pk).build(),
                      ":dirty", AttributeValue.builder().bool(true).build(),
                      ":version",
                          AttributeValue.builder().n(String.valueOf(capturedVersion)).build()))
              .build());
    } catch (ConditionalCheckFailedException e) {
      // version mismatch — SKU stays dirty for the next run
    }
  }

  private void clearDirty(String user, String skuId, int capturedVersion) {
    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var cleanGsi1pk = TcgInventoryItem.USER_PREFIX + user + "#CLEAN";

    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryItem.TABLE_NAME)
              .key(
                  Map.of(
                      TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                      TcgInventoryItem.SK,
                          AttributeValue.builder().s(TcgInventoryItem.formatSkuSk()).build()))
              .updateExpression(
                  "SET "
                      + TcgInventoryItem.DIRTY
                      + " = :clean, "
                      + TcgInventoryItem.GSI1PK
                      + " = :gsi1pk")
              .conditionExpression(
                  TcgInventoryItem.DIRTY
                      + " = :dirty AND "
                      + TcgInventoryItem.VERSION
                      + " = :version")
              .expressionAttributeValues(
                  Map.of(
                      ":clean", AttributeValue.builder().bool(false).build(),
                      ":gsi1pk", AttributeValue.builder().s(cleanGsi1pk).build(),
                      ":dirty", AttributeValue.builder().bool(true).build(),
                      ":version",
                          AttributeValue.builder().n(String.valueOf(capturedVersion)).build()))
              .build());
    } catch (ConditionalCheckFailedException e) {
      // version mismatch — SKU stays dirty for the next run
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
