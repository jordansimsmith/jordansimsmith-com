package com.jordansimsmith.tcginventory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
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

public class RemoveUnitHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(RemoveUnitHandler.class);

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final DynamoDbClient dynamoDbClient;
  private final UlidGenerator ulidGenerator;

  public RemoveUnitHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  RemoveUnitHandler(TcgInventoryFactory factory) {
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
    this.dynamoDbClient = factory.dynamoDbClient();
    this.ulidGenerator = factory.ulidGenerator();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing remove unit request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    // api gateway rest proxy integrations pass path parameters still url-encoded
    var skuId = URLDecoder.decode(event.getPathParameters().get("sku_id"), StandardCharsets.UTF_8);
    var sequenceNumber = Integer.parseInt(event.getPathParameters().get("sequence_number"));
    var queryParams = event.getQueryStringParameters();
    var reason = queryParams != null ? queryParams.get("reason") : null;

    var skuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var unitSk = TcgInventoryItem.formatUnitSk(sequenceNumber);

    var unitItem =
        tcgInventoryTable.getItem(Key.builder().partitionValue(skuPk).sortValue(unitSk).build());
    if (unitItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    if (!"in_stock".equals(unitItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("unit is not in stock"));
    }

    var unitUpdate =
        TransactWriteItem.builder()
            .update(
                Update.builder()
                    .tableName(TcgInventoryItem.TABLE_NAME)
                    .key(
                        Map.of(
                            TcgInventoryItem.PK, AttributeValue.builder().s(skuPk).build(),
                            TcgInventoryItem.SK, AttributeValue.builder().s(unitSk).build()))
                    .updateExpression(
                        "SET #status = :removed, " + TcgInventoryItem.UPDATED_AT + " = :now")
                    .conditionExpression("#status = :inStock")
                    .expressionAttributeNames(Map.of("#status", TcgInventoryItem.STATUS))
                    .expressionAttributeValues(
                        Map.of(
                            ":removed", AttributeValue.builder().s("removed").build(),
                            ":inStock", AttributeValue.builder().s("in_stock").build(),
                            ":now",
                                AttributeValue.builder()
                                    .n(String.valueOf(clock.now().getEpochSecond()))
                                    .build()))
                    .build())
            .build();

    var skuUpdate =
        TransactWriteItem.builder()
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
                            + " :one"
                            + " SET "
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

    var auditItem = new HashMap<String, AttributeValue>();
    auditItem.put(
        TcgInventoryItem.PK,
        AttributeValue.builder().s(TcgInventoryItem.formatAuditPk(user)).build());
    auditItem.put(
        TcgInventoryItem.SK, AttributeValue.builder().s(ulidGenerator.generate()).build());
    auditItem.put(TcgInventoryItem.EVENT_TYPE, AttributeValue.builder().s("adjustment").build());
    auditItem.put(TcgInventoryItem.SKU_ID, AttributeValue.builder().s(skuId).build());
    auditItem.put(
        TcgInventoryItem.SEQUENCE_NUMBER,
        AttributeValue.builder().n(String.valueOf(sequenceNumber)).build());
    auditItem.put(
        TcgInventoryItem.CREATED_AT,
        AttributeValue.builder().n(String.valueOf(clock.now().getEpochSecond())).build());
    if (reason != null && !reason.isEmpty()) {
      auditItem.put(TcgInventoryItem.DECISION_REASON, AttributeValue.builder().s(reason).build());
    }

    var auditPut =
        TransactWriteItem.builder()
            .put(Put.builder().tableName(TcgInventoryItem.TABLE_NAME).item(auditItem).build())
            .build();

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(List.of(unitUpdate, skuUpdate, auditPut))
            .build());

    return httpResponseFactory.noContent();
  }
}
