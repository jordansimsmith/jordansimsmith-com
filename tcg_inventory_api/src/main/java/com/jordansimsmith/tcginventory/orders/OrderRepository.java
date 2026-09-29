package com.jordansimsmith.tcginventory.orders;

import com.jordansimsmith.tcginventory.JobItem;
import com.jordansimsmith.tcginventory.TcgInventoryTable;
import com.jordansimsmith.tcginventory.inventory.InventoryRepository;
import com.jordansimsmith.tcginventory.inventory.SkuItem;
import com.jordansimsmith.tcginventory.inventory.UnitItem;
import com.jordansimsmith.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class OrderRepository {
  private static final String TRANSITION_PREFIX = "ORDER_TRANSITION#";

  private final DynamoDbTable<OrderItem> orderTable;
  private final DynamoDbTable<JobItem> jobTable;
  private final InventoryRepository inventoryRepository;
  private final DynamoDbClient dynamoDbClient;
  private final Clock clock;

  public OrderRepository(
      DynamoDbTable<OrderItem> orderTable,
      DynamoDbTable<JobItem> jobTable,
      InventoryRepository inventoryRepository,
      DynamoDbClient dynamoDbClient,
      Clock clock) {
    this.orderTable = orderTable;
    this.jobTable = jobTable;
    this.inventoryRepository = inventoryRepository;
    this.dynamoDbClient = dynamoDbClient;
    this.clock = clock;
  }

  public List<UnitItem> findUnitsToAllocate(
      String user, String skuId, String orderId, int quantity) {
    return inventoryRepository.findUnitsToAllocate(user, skuId, orderId, quantity);
  }

  public void reserveOrder(String user, OrderItem orderItem) {
    try {
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder()
              .transactItems(
                  List.of(
                      TransactWriteItem.builder()
                          .put(
                              Put.builder()
                                  .tableName(TcgInventoryTable.TABLE_NAME)
                                  .item(orderTable.tableSchema().itemToMap(orderItem, true))
                                  .conditionExpression("attribute_not_exists(" + OrderItem.PK + ")")
                                  .build())
                          .build()))
              .build());
    } catch (TransactionCanceledException e) {
      var current = getOrder(user, orderItem.getOrderId());
      if (current == null) {
        throw e;
      }
    }
    var persistedOrder = getOrder(user, orderItem.getOrderId());
    if (persistedOrder == null) {
      throw new IllegalStateException("reserved order was not persisted");
    }
    resumeReservingOrder(user, persistedOrder);
  }

  public void resumeReservingOrder(String user, OrderItem orderItem) {
    if (!"reserving".equals(orderItem.getStatus())) {
      return;
    }
    var targetStatus = orderItem.getReservationTargetStatus();
    if (targetStatus == null) {
      throw new IllegalStateException("reserving order is missing its target status");
    }
    for (var line : orderItem.getLines()) {
      for (var sequenceNumber : line.getAllocatedSequenceNumbers()) {
        transitionUnit(
            user,
            orderItem.getOrderId(),
            "reserve",
            line.getSkuId(),
            sequenceNumber,
            "reserving",
            "in_stock",
            "reserved",
            orderItem.getOrderId(),
            true);
      }
    }
    verifyTransitionCompletions(user, orderItem, "reserve");
    finishOrderTransition(user, orderItem.getOrderId(), "reserving", targetStatus, "reserve", null);
  }

  public void advanceOrderToPickReady(
      String user, String orderId, String fetchtcgStatus, String fetchtcgCurrentAction) {
    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(
                List.of(
                    buildOrderPickReadyUpdate(user, orderId, fetchtcgStatus, fetchtcgCurrentAction),
                    inventoryRepository.buildAuditPut(
                        user, "payment", Map.of(OrderItem.ORDER_ID, s(orderId)))))
            .build());
  }

  public void updateOrderFulfillment(
      String user,
      String orderId,
      @Nullable String buyerName,
      @Nullable OrderItem.BuyerAddress buyerAddress,
      @Nullable String postageOption) {
    dynamoDbClient.updateItem(
        UpdateItemRequest.builder()
            .tableName(TcgInventoryTable.TABLE_NAME)
            .key(orderKey(user, orderId))
            .updateExpression(
                "SET "
                    + OrderItem.BUYER_NAME
                    + " = :buyerName, "
                    + OrderItem.BUYER_ADDRESS
                    + " = :buyerAddress, "
                    + OrderItem.POSTAGE_OPTION
                    + " = :postageOption, "
                    + OrderItem.UPDATED_AT
                    + " = :now")
            .conditionExpression("attribute_exists(" + OrderItem.PK + ")")
            .expressionAttributeValues(
                Map.of(
                    ":buyerName", toAttributeValue(buyerName),
                    ":buyerAddress", toAttributeValue(buyerAddress),
                    ":postageOption", toAttributeValue(postageOption),
                    ":now", n(clock.now().getEpochSecond())))
            .build());
  }

  public void releaseOrder(String user, OrderItem orderItem, String fetchtcgStatus) {
    if ("awaiting_payment".equals(orderItem.getStatus())) {
      try {
        dynamoDbClient.updateItem(
            UpdateItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(orderKey(user, orderItem.getOrderId()))
                .updateExpression(
                    "SET #status = :releasing, "
                        + OrderItem.FETCHTCG_STATUS
                        + " = :fetchtcgStatus, "
                        + OrderItem.UPDATED_AT
                        + " = :now")
                .conditionExpression("#status = :awaitingPayment")
                .expressionAttributeNames(Map.of("#status", OrderItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":releasing", s("releasing"),
                        ":awaitingPayment", s("awaiting_payment"),
                        ":fetchtcgStatus", s(fetchtcgStatus),
                        ":now", n(clock.now().getEpochSecond())))
                .build());
      } catch (ConditionalCheckFailedException e) {
        var current = getOrder(user, orderItem.getOrderId());
        if (current == null || !"releasing".equals(current.getStatus())) {
          throw e;
        }
      }
    }
    var currentOrder = getOrder(user, orderItem.getOrderId());
    if (currentOrder == null || !"releasing".equals(currentOrder.getStatus())) {
      return;
    }
    resumeReleasingOrder(user, currentOrder);
  }

  public void resumeReleasingOrder(String user, OrderItem orderItem) {
    if (!"releasing".equals(orderItem.getStatus())) {
      return;
    }
    for (var line : orderItem.getLines()) {
      for (var sequenceNumber : line.getAllocatedSequenceNumbers()) {
        transitionUnit(
            user,
            orderItem.getOrderId(),
            "release",
            line.getSkuId(),
            sequenceNumber,
            "releasing",
            "reserved",
            "in_stock",
            null,
            true);
      }
    }
    verifyTransitionCompletions(user, orderItem, "release");
    finishOrderTransition(user, orderItem.getOrderId(), "releasing", "voided", "release", null);
  }

  @Nullable
  public JobItem startFulfillmentJob(String user, String orderId) {
    var jobId = JobItem.formatResourceJobId("fulfill_order", orderId);
    for (int attempt = 0; attempt < 3; attempt++) {
      var order = getOrder(user, orderId);
      if (order == null) {
        throw new IllegalStateException("order not found: " + orderId);
      }
      if ("fulfilled".equals(order.getStatus())) {
        return null;
      }
      if (!"to_pick".equals(order.getStatus()) && !"fulfilling".equals(order.getStatus())) {
        throw new IllegalStateException("order is not ready for fulfillment");
      }

      var job = getJob(user, jobId);
      if (job != null) {
        verifyFulfillmentJob(job, orderId);
        if ("failed".equals(job.getStatus())) {
          var retryJob = resetFailedJob(user, job);
          if ("queued".equals(retryJob.getStatus()) || "running".equals(retryJob.getStatus())) {
            return retryJob;
          }
          if ("succeeded".equals(retryJob.getStatus())) {
            var latest = getOrder(user, orderId);
            if (latest != null && "fulfilled".equals(latest.getStatus())) {
              return null;
            }
          }
          if (attempt < 2) {
            continue;
          }
          throw new IllegalStateException("fulfillment retry job is not active");
        }
        if ("succeeded".equals(job.getStatus())) {
          var latest = getOrder(user, orderId);
          if (latest != null && "fulfilled".equals(latest.getStatus())) {
            return null;
          }
          if (attempt < 2) {
            continue;
          }
          throw new IllegalStateException("fulfillment job succeeded while order is fulfilling");
        }
        if ("fulfilling".equals(order.getStatus())) {
          return job;
        }
        throw new IllegalStateException("fulfillment job exists while order is ready to pick");
      }

      var fulfillmentJob = JobItem.create(user, jobId, "fulfill_order", null, clock.now());
      fulfillmentJob.setOrderId(orderId);
      var jobPut =
          TransactWriteItem.builder()
              .put(
                  Put.builder()
                      .tableName(TcgInventoryTable.TABLE_NAME)
                      .item(jobTable.tableSchema().itemToMap(fulfillmentJob, true))
                      .conditionExpression("attribute_not_exists(" + JobItem.PK + ")")
                      .build())
              .build();
      var parentTransition =
          "to_pick".equals(order.getStatus())
              ? TransactWriteItem.builder()
                  .update(
                      Update.builder()
                          .tableName(TcgInventoryTable.TABLE_NAME)
                          .key(orderKey(user, orderId))
                          .updateExpression(
                              "SET #status = :fulfilling, " + OrderItem.UPDATED_AT + " = :now")
                          .conditionExpression("#status = :toPick")
                          .expressionAttributeNames(Map.of("#status", OrderItem.STATUS))
                          .expressionAttributeValues(
                              Map.of(
                                  ":fulfilling", s("fulfilling"),
                                  ":toPick", s("to_pick"),
                                  ":now", n(clock.now().getEpochSecond())))
                          .build())
                  .build()
              : buildOrderCheck(user, orderId, "fulfilling");
      try {
        dynamoDbClient.transactWriteItems(
            TransactWriteItemsRequest.builder()
                .transactItems(List.of(parentTransition, jobPut))
                .build());
        return fulfillmentJob;
      } catch (TransactionCanceledException e) {
        if (attempt == 2) {
          throw e;
        }
      }
    }
    throw new IllegalStateException("could not create order fulfillment job");
  }

  private void verifyFulfillmentJob(JobItem job, String orderId) {
    if (!"fulfill_order".equals(job.getJobType()) || !orderId.equals(job.getOrderId())) {
      throw new IllegalStateException("fulfillment job does not match order " + orderId);
    }
  }

  private JobItem resetFailedJob(String user, JobItem job) {
    var jobId = job.getJobId();
    var nextContinuation = job.getContinuation() == null ? 1 : job.getContinuation() + 1;
    try {
      dynamoDbClient.updateItem(
          UpdateItemRequest.builder()
              .tableName(TcgInventoryTable.TABLE_NAME)
              .key(jobKey(user, jobId))
              .updateExpression(
                  "SET #status = :queued, "
                      + JobItem.UPDATED_AT
                      + " = :now, "
                      + JobItem.PROCESSED_COUNT
                      + " = :zero, "
                      + JobItem.CONTINUATION
                      + " = :continuation REMOVE #error")
              .conditionExpression("#status = :failed")
              .expressionAttributeNames(Map.of("#status", JobItem.STATUS, "#error", JobItem.ERROR))
              .expressionAttributeValues(
                  Map.of(
                      ":queued", s("queued"),
                      ":failed", s("failed"),
                      ":zero", n(0),
                      ":continuation", n(nextContinuation),
                      ":now", n(clock.now().getEpochSecond())))
              .build());
    } catch (ConditionalCheckFailedException e) {
      var current = getJob(user, jobId);
      if (current == null) {
        throw e;
      }
      return current;
    }
    var current = getJob(user, jobId);
    if (current == null) {
      throw new IllegalStateException("fulfillment job is missing: " + jobId);
    }
    return current;
  }

  public void sellOrder(String user, OrderItem orderItem) {
    if (!"fulfilling".equals(orderItem.getStatus())) {
      return;
    }
    for (var line : orderItem.getLines()) {
      for (var sequenceNumber : line.getAllocatedSequenceNumbers()) {
        transitionUnit(
            user,
            orderItem.getOrderId(),
            "fulfill",
            line.getSkuId(),
            sequenceNumber,
            "fulfilling",
            "reserved",
            "sold",
            orderItem.getOrderId(),
            false);
      }
    }
    verifyTransitionCompletions(user, orderItem, "fulfill");
    finishOrderTransition(user, orderItem.getOrderId(), "fulfilling", "fulfilled", "sell", null);
  }

  public OrderItem getOrder(String user, String orderId) {
    var response =
        dynamoDbClient.getItem(
            GetItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(orderKey(user, orderId))
                .consistentRead(true)
                .build());
    return response.hasItem() ? orderTable.tableSchema().mapToItem(response.item()) : null;
  }

  @Nullable
  public JobItem getJob(String user, String jobId) {
    var response =
        dynamoDbClient.getItem(
            GetItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(
                    Map.of(
                        JobItem.PK, s(JobItem.formatPk(user)),
                        JobItem.SK, s(JobItem.formatSk(jobId))))
                .consistentRead(true)
                .build());
    return response.hasItem() ? jobTable.tableSchema().mapToItem(response.item()) : null;
  }

  @Nullable
  public String getFulfillmentError(String user, OrderItem orderItem) {
    if (!"fulfilling".equals(orderItem.getStatus())) {
      return null;
    }
    var jobId = JobItem.formatResourceJobId("fulfill_order", orderItem.getOrderId());
    var job = getJob(user, jobId);
    if (job == null) {
      throw new IllegalStateException("fulfillment job is missing: " + jobId);
    }
    verifyFulfillmentJob(job, orderItem.getOrderId());
    return job.getError();
  }

  private void transitionUnit(
      String user,
      String orderId,
      String action,
      String skuId,
      int sequenceNumber,
      String parentStatus,
      String beforeStatus,
      String afterStatus,
      @Nullable String afterOwner,
      boolean dirtySku) {
    if (hasCompletion(user, orderId, action, skuId, sequenceNumber)) {
      return;
    }
    var unit = inventoryRepository.getUnitConsistent(user, skuId, sequenceNumber);
    if (unit == null) {
      throw new IllegalStateException(
          "order unit is missing: " + skuId + " sequence " + sequenceNumber);
    }
    var ownerMatches =
        "reserve".equals(action) ? unit.getOrderId() == null : orderId.equals(unit.getOrderId());
    var exactBeforeState = beforeStatus.equals(unit.getStatus()) && ownerMatches;
    var legacyAfterState =
        afterStatus.equals(unit.getStatus())
            && (afterOwner == null
                ? unit.getOrderId() == null
                : afterOwner.equals(unit.getOrderId()));
    if (!exactBeforeState && legacyAfterState && !"release".equals(action)) {
      completeLegacyUnit(user, orderId, action, skuId, sequenceNumber, parentStatus, unit);
      return;
    }
    if (!exactBeforeState) {
      throw new IllegalStateException(
          "order unit state is ambiguous for "
              + action
              + ": "
              + skuId
              + " sequence "
              + sequenceNumber);
    }

    var audit =
        inventoryRepository.buildAuditPut(
            user,
            "unit_" + ("fulfill".equals(action) ? "sell" : action),
            transitionAttributes(
                orderId, action, skuId, sequenceNumber, beforeStatus, afterStatus));
    var marker =
        buildCompletionPut(
            user, orderId, action, skuId, sequenceNumber, beforeStatus, afterStatus, afterOwner);
    var skuUpdate =
        dirtySku
            ? inventoryRepository.buildSkuDirtyUpdate(user, skuId)
            : inventoryRepository.buildSkuVersionBump(user, skuId);
    var unitUpdate =
        switch (action) {
          case "reserve" ->
              inventoryRepository.buildUnitReserveUpdate(user, skuId, sequenceNumber, orderId);
          case "release" ->
              inventoryRepository.buildUnitReleaseUpdate(user, skuId, sequenceNumber, orderId);
          case "fulfill" ->
              inventoryRepository.buildUnitSellUpdate(user, skuId, sequenceNumber, orderId);
          default -> throw new IllegalArgumentException("unknown order transition: " + action);
        };
    try {
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder()
              .transactItems(
                  List.of(
                      buildOrderCheck(user, orderId, parentStatus),
                      unitUpdate,
                      skuUpdate,
                      audit,
                      marker))
              .build());
    } catch (TransactionCanceledException e) {
      if (!hasCompletion(user, orderId, action, skuId, sequenceNumber)) {
        throw e;
      }
    }
  }

  private void verifyTransitionCompletions(String user, OrderItem orderItem, String action) {
    for (var line : orderItem.getLines()) {
      for (var sequenceNumber : line.getAllocatedSequenceNumbers()) {
        if (!hasCompletion(user, orderItem.getOrderId(), action, line.getSkuId(), sequenceNumber)) {
          throw new IllegalStateException(
              "order transition is missing a completion record for "
                  + line.getSkuId()
                  + " sequence "
                  + sequenceNumber);
        }
      }
    }
  }

  private void completeLegacyUnit(
      String user,
      String orderId,
      String action,
      String skuId,
      int sequenceNumber,
      String parentStatus,
      UnitItem unit) {
    var beforeStatus =
        switch (action) {
          case "reserve" -> "in_stock";
          case "release", "fulfill" -> "reserved";
          default -> throw new IllegalArgumentException("unknown order transition: " + action);
        };
    var afterStatus =
        switch (action) {
          case "reserve" -> "reserved";
          case "release" -> "in_stock";
          case "fulfill" -> "sold";
          default -> throw new IllegalArgumentException("unknown order transition: " + action);
        };
    var afterOwner = "release".equals(action) ? null : orderId;
    var unitCheck =
        TransactWriteItem.builder()
            .conditionCheck(
                ConditionCheck.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(Map.of(UnitItem.PK, s(unit.getPk()), UnitItem.SK, s(unit.getSk())))
                    .conditionExpression("#status = :status AND " + ownerCondition(afterOwner))
                    .expressionAttributeNames(Map.of("#status", UnitItem.STATUS))
                    .expressionAttributeValues(
                        afterOwner == null
                            ? Map.of(":status", s(afterStatus))
                            : Map.of(":status", s(afterStatus), ":owner", s(afterOwner)))
                    .build())
            .build();
    var audit =
        inventoryRepository.buildAuditPut(
            user,
            "unit_" + ("fulfill".equals(action) ? "sell" : action) + "_recovered",
            transitionAttributes(
                orderId, action, skuId, sequenceNumber, beforeStatus, afterStatus));
    var marker =
        buildCompletionPut(
            user, orderId, action, skuId, sequenceNumber, beforeStatus, afterStatus, afterOwner);
    try {
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder()
              .transactItems(
                  List.of(buildOrderCheck(user, orderId, parentStatus), unitCheck, audit, marker))
              .build());
    } catch (TransactionCanceledException e) {
      if (!hasCompletion(user, orderId, action, skuId, sequenceNumber)) {
        throw e;
      }
    }
  }

  private boolean hasCompletion(
      String user, String orderId, String action, String skuId, int sequenceNumber) {
    var response =
        dynamoDbClient.getItem(
            GetItemRequest.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(transitionKey(user, orderId, action, skuId, sequenceNumber))
                .consistentRead(true)
                .build());
    return response.hasItem();
  }

  private TransactWriteItem buildCompletionPut(
      String user,
      String orderId,
      String action,
      String skuId,
      int sequenceNumber,
      String beforeStatus,
      String afterStatus,
      @Nullable String afterOwner) {
    var item = new HashMap<String, AttributeValue>();
    item.put(OrderItem.PK, s(OrderItem.formatPk(user)));
    item.put(OrderItem.SK, s(transitionSk(orderId, action, skuId, sequenceNumber)));
    item.put(OrderItem.ORDER_ID, s(orderId));
    item.put("action", s(action));
    item.put(SkuItem.SKU_ID, s(skuId));
    item.put(UnitItem.SEQUENCE_NUMBER, n(sequenceNumber));
    item.put("before_status", s(beforeStatus));
    item.put("after_status", s(afterStatus));
    item.put("before_owner", s("reserve".equals(action) ? "none" : orderId));
    item.put("after_owner", s(afterOwner == null ? "none" : afterOwner));
    item.put("completed_at", n(clock.now().getEpochSecond()));
    return TransactWriteItem.builder()
        .put(
            Put.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .item(item)
                .conditionExpression("attribute_not_exists(" + OrderItem.PK + ")")
                .build())
        .build();
  }

  private TransactWriteItem buildOrderCheck(String user, String orderId, String status) {
    return TransactWriteItem.builder()
        .conditionCheck(
            ConditionCheck.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(orderKey(user, orderId))
                .conditionExpression("#status = :status")
                .expressionAttributeNames(Map.of("#status", OrderItem.STATUS))
                .expressionAttributeValues(Map.of(":status", s(status)))
                .build())
        .build();
  }

  private void finishOrderTransition(
      String user,
      String orderId,
      String expectedStatus,
      String finalStatus,
      String auditType,
      @Nullable String fetchTcgStatus) {
    var expression =
        new StringBuilder(
            "SET #status = :finalStatus, " + OrderItem.UPDATED_AT + " = :now REMOVE ");
    expression.append(
        switch (expectedStatus) {
          case "reserving" -> OrderItem.RESERVATION_TARGET_STATUS;
          case "releasing" -> OrderItem.FETCHTCG_CURRENT_ACTION;
          case "fulfilling" -> "fulfillment_error, fulfillment_job_id";
          default ->
              throw new IllegalArgumentException("unknown order transition: " + expectedStatus);
        });
    var values = new HashMap<String, AttributeValue>();
    values.put(":finalStatus", s(finalStatus));
    values.put(":expectedStatus", s(expectedStatus));
    values.put(":now", n(clock.now().getEpochSecond()));
    if (fetchTcgStatus != null) {
      expression.insert(
          expression.indexOf(" REMOVE"), ", " + OrderItem.FETCHTCG_STATUS + " = :fetchtcgStatus");
      values.put(":fetchtcgStatus", s(fetchTcgStatus));
    }
    var update =
        TransactWriteItem.builder()
            .update(
                Update.builder()
                    .tableName(TcgInventoryTable.TABLE_NAME)
                    .key(orderKey(user, orderId))
                    .updateExpression(expression.toString())
                    .conditionExpression("#status = :expectedStatus")
                    .expressionAttributeNames(Map.of("#status", OrderItem.STATUS))
                    .expressionAttributeValues(values)
                    .build())
            .build();
    var audit =
        inventoryRepository.buildAuditPut(user, auditType, Map.of(OrderItem.ORDER_ID, s(orderId)));
    try {
      dynamoDbClient.transactWriteItems(
          TransactWriteItemsRequest.builder().transactItems(List.of(update, audit)).build());
    } catch (TransactionCanceledException e) {
      var current = getOrder(user, orderId);
      if (current == null || !finalStatus.equals(current.getStatus())) {
        throw e;
      }
    }
  }

  private TransactWriteItem buildOrderPickReadyUpdate(
      String user, String orderId, String fetchtcgStatus, String fetchtcgCurrentAction) {
    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryTable.TABLE_NAME)
                .key(orderKey(user, orderId))
                .updateExpression(
                    "SET #status = :toPick, "
                        + OrderItem.FETCHTCG_STATUS
                        + " = :fetchtcgStatus, "
                        + OrderItem.FETCHTCG_CURRENT_ACTION
                        + " = :fetchtcgCurrentAction, "
                        + OrderItem.UPDATED_AT
                        + " = :now")
                .conditionExpression("#status = :awaitingPayment")
                .expressionAttributeNames(Map.of("#status", OrderItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":toPick", s("to_pick"),
                        ":awaitingPayment", s("awaiting_payment"),
                        ":fetchtcgStatus", s(fetchtcgStatus),
                        ":fetchtcgCurrentAction", s(fetchtcgCurrentAction),
                        ":now", n(clock.now().getEpochSecond())))
                .build())
        .build();
  }

  private Map<String, AttributeValue> transitionAttributes(
      String orderId,
      String action,
      String skuId,
      int sequenceNumber,
      String beforeStatus,
      String afterStatus) {
    var beforeOwner = "reserve".equals(action) ? "none" : orderId;
    var afterOwner = "release".equals(action) ? "none" : orderId;
    return Map.of(
        OrderItem.ORDER_ID,
        s(orderId),
        "action",
        s(action),
        SkuItem.SKU_ID,
        s(skuId),
        UnitItem.SEQUENCE_NUMBER,
        n(sequenceNumber),
        "before_status",
        s(beforeStatus),
        "after_status",
        s(afterStatus),
        "before_owner",
        s(beforeOwner),
        "after_owner",
        s(afterOwner));
  }

  private static String ownerCondition(@Nullable String owner) {
    return owner == null ? "attribute_not_exists(order_id)" : "order_id = :owner";
  }

  private static String transitionSk(
      String orderId, String action, String skuId, int sequenceNumber) {
    return TRANSITION_PREFIX
        + orderId
        + "#"
        + action
        + "#"
        + skuId
        + "#"
        + String.format("%010d", sequenceNumber);
  }

  private static Map<String, AttributeValue> transitionKey(
      String user, String orderId, String action, String skuId, int sequenceNumber) {
    return Map.of(
        OrderItem.PK, s(OrderItem.formatPk(user)),
        OrderItem.SK, s(transitionSk(orderId, action, skuId, sequenceNumber)));
  }

  private static Map<String, AttributeValue> orderKey(String user, String orderId) {
    return Map.of(
        OrderItem.PK, s(OrderItem.formatPk(user)), OrderItem.SK, s(OrderItem.formatSk(orderId)));
  }

  private static Map<String, AttributeValue> jobKey(String user, String jobId) {
    return Map.of(JobItem.PK, s(JobItem.formatPk(user)), JobItem.SK, s(JobItem.formatSk(jobId)));
  }

  private static AttributeValue toAttributeValue(@Nullable String value) {
    return value == null ? AttributeValue.builder().nul(true).build() : s(value);
  }

  private static AttributeValue toAttributeValue(@Nullable OrderItem.BuyerAddress address) {
    if (address == null) {
      return AttributeValue.builder().nul(true).build();
    }
    var parts = new HashMap<String, AttributeValue>();
    putAddressPart(parts, OrderItem.BuyerAddress.LINE1, address.getLine1());
    putAddressPart(parts, OrderItem.BuyerAddress.LINE2, address.getLine2());
    putAddressPart(parts, OrderItem.BuyerAddress.SUBURB, address.getSuburb());
    putAddressPart(parts, OrderItem.BuyerAddress.CITY, address.getCity());
    putAddressPart(parts, OrderItem.BuyerAddress.POST_CODE, address.getPostCode());
    putAddressPart(parts, OrderItem.BuyerAddress.COUNTRY, address.getCountry());
    return AttributeValue.builder().m(parts).build();
  }

  private static void putAddressPart(
      Map<String, AttributeValue> parts, String attribute, @Nullable String value) {
    if (value != null) {
      parts.put(attribute, s(value));
    }
  }

  private static AttributeValue s(String value) {
    return AttributeValue.builder().s(value).build();
  }

  private static AttributeValue n(long value) {
    return AttributeValue.builder().n(String.valueOf(value)).build();
  }
}
