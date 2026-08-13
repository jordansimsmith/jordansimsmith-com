package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

public class ConfirmOrderHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfirmOrderHandler.class);

  record ConfirmOrderResponse(
      @JsonProperty("order_id") String orderId, @JsonProperty("state") String state) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final DynamoDbClient dynamoDbClient;
  private final UlidGenerator ulidGenerator;
  private final ObjectMapper objectMapper;

  public ConfirmOrderHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  ConfirmOrderHandler(TcgInventoryFactory factory) {
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
    this.dynamoDbClient = factory.dynamoDbClient();
    this.ulidGenerator = factory.ulidGenerator();
    this.objectMapper = factory.objectMapper();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing confirm order request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var orderId = event.getPathParameters().get("order_id");

    var orderKey =
        Key.builder()
            .partitionValue(TcgInventoryItem.formatUserPk(user))
            .sortValue(TcgInventoryItem.formatOrderSk(orderId))
            .build();

    var orderItem = tcgInventoryTable.getItem(orderKey);
    if (orderItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    if (!"to_pick".equals(orderItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("order is not ready to pick"));
    }

    var orderLines = OrderLines.parse(orderItem.getLines(), objectMapper);
    var transactItems = new ArrayList<TransactWriteItem>();
    var affectedSkuIds = new HashSet<String>();

    for (var line : orderLines) {
      for (var seqNum : line.allocatedSequenceNumbers()) {
        transactItems.add(buildUnitSellUpdate(user, line.skuId(), seqNum));
      }
      if (affectedSkuIds.add(line.skuId())) {
        transactItems.add(buildSkuVersionBump(user, line.skuId()));
      }
    }

    transactItems.add(buildOrderFulfilledUpdate(user, orderId));

    var auditItem = new HashMap<String, AttributeValue>();
    auditItem.put(
        TcgInventoryItem.PK,
        AttributeValue.builder().s(TcgInventoryItem.formatAuditPk(user)).build());
    auditItem.put(
        TcgInventoryItem.SK, AttributeValue.builder().s(ulidGenerator.generate()).build());
    auditItem.put(TcgInventoryItem.EVENT_TYPE, AttributeValue.builder().s("sell").build());
    auditItem.put(TcgInventoryItem.ORDER_ID, AttributeValue.builder().s(orderId).build());
    auditItem.put(
        TcgInventoryItem.CREATED_AT,
        AttributeValue.builder().n(String.valueOf(clock.now().getEpochSecond())).build());
    transactItems.add(
        TransactWriteItem.builder()
            .put(Put.builder().tableName(TcgInventoryItem.TABLE_NAME).item(auditItem).build())
            .build());

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder().transactItems(transactItems).build());

    return httpResponseFactory.ok(new ConfirmOrderResponse(orderId, "fulfilled"));
  }

  private TransactWriteItem buildUnitSellUpdate(String user, String skuId, int sequenceNumber) {
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
                .updateExpression("SET #status = :sold, " + TcgInventoryItem.UPDATED_AT + " = :now")
                .conditionExpression("#status = :reserved")
                .expressionAttributeNames(Map.of("#status", TcgInventoryItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":sold", AttributeValue.builder().s("sold").build(),
                        ":reserved", AttributeValue.builder().s("reserved").build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  private TransactWriteItem buildOrderFulfilledUpdate(String user, String orderId) {
    var userPk = TcgInventoryItem.formatUserPk(user);
    var orderSk = TcgInventoryItem.formatOrderSk(orderId);

    return TransactWriteItem.builder()
        .update(
            Update.builder()
                .tableName(TcgInventoryItem.TABLE_NAME)
                .key(
                    Map.of(
                        TcgInventoryItem.PK, AttributeValue.builder().s(userPk).build(),
                        TcgInventoryItem.SK, AttributeValue.builder().s(orderSk).build()))
                .updateExpression(
                    "SET #status = :fulfilled, " + TcgInventoryItem.UPDATED_AT + " = :now")
                .conditionExpression("#status = :toPick")
                .expressionAttributeNames(Map.of("#status", TcgInventoryItem.STATUS))
                .expressionAttributeValues(
                    Map.of(
                        ":fulfilled", AttributeValue.builder().s("fulfilled").build(),
                        ":toPick", AttributeValue.builder().s("to_pick").build(),
                        ":now",
                            AttributeValue.builder()
                                .n(String.valueOf(clock.now().getEpochSecond()))
                                .build()))
                .build())
        .build();
  }

  private TransactWriteItem buildSkuVersionBump(String user, String skuId) {
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
                .updateExpression("ADD " + TcgInventoryItem.VERSION + " :one")
                .expressionAttributeValues(Map.of(":one", AttributeValue.builder().n("1").build()))
                .build())
        .build();
  }
}
