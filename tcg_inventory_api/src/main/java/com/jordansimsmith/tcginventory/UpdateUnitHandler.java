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
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

public class UpdateUnitHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateUnitHandler.class);

  record UpdateUnitRequest(@JsonProperty("condition") String condition) {}

  record UpdateUnitResponse(@JsonProperty("sku_id") String skuId) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private final Clock clock;
  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<TcgInventoryItem> tcgInventoryTable;
  private final DynamoDbClient dynamoDbClient;
  private final ObjectMapper objectMapper;
  private final UlidGenerator ulidGenerator;

  public UpdateUnitHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  UpdateUnitHandler(TcgInventoryFactory factory) {
    this.clock = factory.clock();
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.tcgInventoryTable = factory.tcgInventoryTable();
    this.dynamoDbClient = factory.dynamoDbClient();
    this.objectMapper = factory.objectMapper();
    this.ulidGenerator = factory.ulidGenerator();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing update unit request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    // api gateway rest proxy integrations pass path parameters still url-encoded
    var skuId = URLDecoder.decode(event.getPathParameters().get("sku_id"), StandardCharsets.UTF_8);
    var sequenceNumber = Integer.parseInt(event.getPathParameters().get("sequence_number"));

    UpdateUnitRequest body;
    try {
      body = objectMapper.readValue(event.getBody(), UpdateUnitRequest.class);
    } catch (Exception e) {
      return httpResponseFactory.badRequest(new ErrorResponse("invalid request body"));
    }

    try {
      if (body.condition() == null) {
        return httpResponseFactory.badRequest(new ErrorResponse("invalid condition"));
      }
      Condition.valueOf(body.condition());
    } catch (IllegalArgumentException e) {
      return httpResponseFactory.badRequest(new ErrorResponse("invalid condition"));
    }

    var sourceSkuPk = TcgInventoryItem.formatSkuPk(user, skuId);
    var unitSk = TcgInventoryItem.formatUnitSk(sequenceNumber);

    var skuItem =
        tcgInventoryTable.getItem(
            Key.builder()
                .partitionValue(sourceSkuPk)
                .sortValue(TcgInventoryItem.formatSkuSk())
                .build());
    if (skuItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var unitItem =
        tcgInventoryTable.getItem(
            Key.builder().partitionValue(sourceSkuPk).sortValue(unitSk).build());
    if (unitItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    if (!"in_stock".equals(unitItem.getStatus())) {
      return httpResponseFactory.conflict(new ErrorResponse("unit is not in stock"));
    }

    if (body.condition().equals(skuItem.getCondition())) {
      return httpResponseFactory.conflict(new ErrorResponse("condition is unchanged"));
    }

    var targetSkuId = skuItem.getScryfallId() + "#" + skuItem.getFinish() + "#" + body.condition();
    var targetSkuPk = TcgInventoryItem.formatSkuPk(user, targetSkuId);

    var deleteUnit =
        TransactWriteItem.builder()
            .delete(
                Delete.builder()
                    .tableName(TcgInventoryItem.TABLE_NAME)
                    .key(
                        Map.of(
                            TcgInventoryItem.PK, AttributeValue.builder().s(sourceSkuPk).build(),
                            TcgInventoryItem.SK, AttributeValue.builder().s(unitSk).build()))
                    .conditionExpression("#status = :inStock")
                    .expressionAttributeNames(Map.of("#status", TcgInventoryItem.STATUS))
                    .expressionAttributeValues(
                        Map.of(":inStock", AttributeValue.builder().s("in_stock").build()))
                    .build())
            .build();

    var newUnitItem = new HashMap<String, AttributeValue>();
    newUnitItem.put(TcgInventoryItem.PK, AttributeValue.builder().s(targetSkuPk).build());
    newUnitItem.put(TcgInventoryItem.SK, AttributeValue.builder().s(unitSk).build());
    newUnitItem.put(
        TcgInventoryItem.SEQUENCE_NUMBER,
        AttributeValue.builder().n(String.valueOf(sequenceNumber)).build());
    newUnitItem.put(TcgInventoryItem.STATUS, AttributeValue.builder().s("in_stock").build());
    newUnitItem.put(
        TcgInventoryItem.IMPORT_ID, AttributeValue.builder().s(unitItem.getImportId()).build());
    newUnitItem.put(
        TcgInventoryItem.CREATED_AT,
        AttributeValue.builder()
            .n(String.valueOf(unitItem.getCreatedAt().getEpochSecond()))
            .build());

    var putUnit =
        TransactWriteItem.builder()
            .put(Put.builder().tableName(TcgInventoryItem.TABLE_NAME).item(newUnitItem).build())
            .build();

    var sourceSkuUpdate =
        TransactWriteItem.builder()
            .update(
                Update.builder()
                    .tableName(TcgInventoryItem.TABLE_NAME)
                    .key(
                        Map.of(
                            TcgInventoryItem.PK, AttributeValue.builder().s(sourceSkuPk).build(),
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

    var targetSkuUpdate =
        TransactWriteItem.builder()
            .update(
                Update.builder()
                    .tableName(TcgInventoryItem.TABLE_NAME)
                    .key(
                        Map.of(
                            TcgInventoryItem.PK, AttributeValue.builder().s(targetSkuPk).build(),
                            TcgInventoryItem.SK,
                                AttributeValue.builder().s(TcgInventoryItem.formatSkuSk()).build()))
                    .updateExpression(
                        "ADD "
                            + TcgInventoryItem.VERSION
                            + " :one"
                            + " SET "
                            + TcgInventoryItem.SKU_ID
                            + " = :skuId, "
                            + TcgInventoryItem.SCRYFALL_ID
                            + " = :scryfallId, "
                            + "#finish = :finish, "
                            + "#condition = :condition, "
                            + "#name = :cardName, "
                            + TcgInventoryItem.SET_CODE
                            + " = :setCode, "
                            + TcgInventoryItem.SET_NAME
                            + " = :setName, "
                            + TcgInventoryItem.COLLECTOR_NUMBER
                            + " = :collectorNumber, "
                            + TcgInventoryItem.DIRTY
                            + " = :dirty, "
                            + TcgInventoryItem.GSI1PK
                            + " = :gsi1pk, "
                            + TcgInventoryItem.GSI1SK
                            + " = :gsi1sk, "
                            + TcgInventoryItem.GSI2PK
                            + " = :gsi2pk, "
                            + TcgInventoryItem.GSI2SK
                            + " = :gsi2sk")
                    .expressionAttributeNames(
                        Map.of(
                            "#finish", TcgInventoryItem.FINISH,
                            "#condition", TcgInventoryItem.CONDITION,
                            "#name", TcgInventoryItem.NAME))
                    .expressionAttributeValues(
                        Map.ofEntries(
                            Map.entry(":one", AttributeValue.builder().n("1").build()),
                            Map.entry(":skuId", AttributeValue.builder().s(targetSkuId).build()),
                            Map.entry(
                                ":scryfallId",
                                AttributeValue.builder().s(skuItem.getScryfallId()).build()),
                            Map.entry(
                                ":finish", AttributeValue.builder().s(skuItem.getFinish()).build()),
                            Map.entry(
                                ":condition", AttributeValue.builder().s(body.condition()).build()),
                            Map.entry(
                                ":cardName", AttributeValue.builder().s(skuItem.getName()).build()),
                            Map.entry(
                                ":setCode",
                                AttributeValue.builder().s(skuItem.getSetCode()).build()),
                            Map.entry(
                                ":setName",
                                AttributeValue.builder().s(skuItem.getSetName()).build()),
                            Map.entry(
                                ":collectorNumber",
                                AttributeValue.builder().s(skuItem.getCollectorNumber()).build()),
                            Map.entry(":dirty", AttributeValue.builder().bool(true).build()),
                            Map.entry(
                                ":gsi1pk",
                                AttributeValue.builder()
                                    .s(TcgInventoryItem.formatGsi1pk(user))
                                    .build()),
                            Map.entry(
                                ":gsi1sk",
                                AttributeValue.builder()
                                    .s(TcgInventoryItem.formatGsi1sk(targetSkuId))
                                    .build()),
                            Map.entry(
                                ":gsi2pk",
                                AttributeValue.builder()
                                    .s(TcgInventoryItem.formatGsi2pk(user))
                                    .build()),
                            Map.entry(
                                ":gsi2sk",
                                AttributeValue.builder()
                                    .s(
                                        TcgInventoryItem.formatGsi2sk(
                                            skuItem.getName().toLowerCase(), targetSkuId))
                                    .build())))
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

    var auditPut =
        TransactWriteItem.builder()
            .put(Put.builder().tableName(TcgInventoryItem.TABLE_NAME).item(auditItem).build())
            .build();

    dynamoDbClient.transactWriteItems(
        TransactWriteItemsRequest.builder()
            .transactItems(List.of(deleteUnit, putUnit, sourceSkuUpdate, targetSkuUpdate, auditPut))
            .build());

    return httpResponseFactory.ok(new UpdateUnitResponse(targetSkuId));
  }
}
