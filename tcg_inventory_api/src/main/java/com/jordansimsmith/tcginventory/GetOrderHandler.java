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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class GetOrderHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final Logger LOGGER = LoggerFactory.getLogger(GetOrderHandler.class);

  record NeighborCardResponse(
      @JsonProperty("name") String name,
      @JsonProperty("set_code") String setCode,
      @JsonProperty("collector_number") String collectorNumber,
      @JsonProperty("finish") String finish,
      @JsonProperty("condition") String condition) {}

  record OrderUnitResponse(
      @JsonProperty("sequence_number") int sequenceNumber,
      @JsonProperty("location") String location,
      @JsonProperty("current_location") String currentLocation,
      @JsonProperty("name") String name,
      @JsonProperty("set_code") String setCode,
      @JsonProperty("collector_number") String collectorNumber,
      @JsonProperty("finish") String finish,
      @JsonProperty("condition") String condition,
      @JsonProperty("price") @Nullable String price,
      @JsonProperty("previous_card") @Nullable NeighborCardResponse previousCard,
      @JsonProperty("next_card") @Nullable NeighborCardResponse nextCard) {}

  record OrderLineResponse(
      @JsonProperty("name") String name,
      @JsonProperty("set_code") String setCode,
      @JsonProperty("collector_number") String collectorNumber,
      @JsonProperty("finish") String finish,
      @JsonProperty("condition") String condition,
      @JsonProperty("quantity") int quantity,
      @JsonProperty("price") @Nullable String price,
      @JsonProperty("listed_price") @Nullable String listedPrice) {}

  record BuyerAddressResponse(
      @JsonProperty("line1") @Nullable String line1,
      @JsonProperty("line2") @Nullable String line2,
      @JsonProperty("suburb") @Nullable String suburb,
      @JsonProperty("city") @Nullable String city,
      @JsonProperty("post_code") @Nullable String postCode,
      @JsonProperty("country") @Nullable String country) {}

  record OrderDetailResponse(
      @JsonProperty("order_id") String orderId,
      @JsonProperty("state") String state,
      @JsonProperty("accepted_at") long acceptedAt,
      @JsonProperty("delivery_mode") @Nullable String deliveryMode,
      @JsonProperty("buyer_name") @Nullable String buyerName,
      @JsonProperty("buyer_address") @Nullable BuyerAddressResponse buyerAddress,
      @JsonProperty("postage_option") @Nullable String postageOption,
      @JsonProperty("total_price") @Nullable String totalPrice,
      @JsonProperty("items_total_price") @Nullable String itemsTotalPrice,
      @JsonProperty("listed_total_price") @Nullable String listedTotalPrice,
      @JsonProperty("unit_count") int unitCount,
      @JsonProperty("lines") List<OrderLineResponse> lines,
      @JsonProperty("units") List<OrderUnitResponse> units) {}

  record ErrorResponse(@JsonProperty("message") String message) {}

  private record BlockPosition(
      String currentLocation, @Nullable UnitItem previousUnit, @Nullable UnitItem nextUnit) {}

  private final RequestContextFactory requestContextFactory;
  private final HttpResponseFactory httpResponseFactory;
  private final DynamoDbTable<OrderItem> orderTable;
  private final DynamoDbTable<UnitItem> unitTable;
  private final DynamoDbTable<SkuItem> skuTable;
  private final ObjectMapper objectMapper;

  public GetOrderHandler() {
    this(TcgInventoryFactory.create());
  }

  @VisibleForTesting
  GetOrderHandler(TcgInventoryFactory factory) {
    this.requestContextFactory = factory.requestContextFactory();
    this.httpResponseFactory = factory.httpResponseFactory();
    this.orderTable = factory.orderTable();
    this.unitTable = factory.unitTable();
    this.skuTable = factory.skuTable();
    this.objectMapper = factory.objectMapper();
  }

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
    try {
      return doHandleRequest(event);
    } catch (Exception e) {
      LOGGER.error("error processing get order request", e);
      throw new RuntimeException(e);
    }
  }

  private APIGatewayV2HTTPResponse doHandleRequest(APIGatewayV2HTTPEvent event) {
    var user = requestContextFactory.createCtx(event).user();
    var orderId = event.getPathParameters().get("order_id");

    String orderSk;
    try {
      orderSk = OrderItem.formatSk(orderId);
    } catch (IllegalArgumentException e) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var orderKey =
        Key.builder().partitionValue(SkuItem.formatUserPk(user)).sortValue(orderSk).build();

    var orderItem = orderTable.getItem(orderKey);
    if (orderItem == null) {
      return httpResponseFactory.notFound(new ErrorResponse("Not Found"));
    }

    var orderLines = OrderLines.parse(orderItem.getLines(), objectMapper);
    var blockUnits = findBlockUnits(user, orderLines);

    Map<String, SkuItem> skuCache = new HashMap<>();
    var units = new ArrayList<OrderUnitResponse>();
    var lines = new ArrayList<OrderLineResponse>();

    for (var line : orderLines) {
      var skuItem = getSku(SkuItem.formatPk(user, line.skuId()), skuCache);

      if (skuItem == null) {
        continue;
      }

      lines.add(
          new OrderLineResponse(
              skuItem.getName(),
              skuItem.getSetCode(),
              skuItem.getCollectorNumber(),
              skuItem.getFinish(),
              skuItem.getCondition(),
              line.quantity(),
              line.price(),
              line.listedPrice()));

      var unitPrice = perUnitPrice(line);
      for (var seqNum : line.allocatedSequenceNumbers()) {
        var position = computeBlockPosition(blockUnits, seqNum);
        units.add(
            new OrderUnitResponse(
                seqNum,
                InventoryLocation.formatLocation(seqNum),
                position.currentLocation(),
                skuItem.getName(),
                skuItem.getSetCode(),
                skuItem.getCollectorNumber(),
                skuItem.getFinish(),
                skuItem.getCondition(),
                unitPrice,
                toNeighborCard(position.previousUnit(), skuCache),
                toNeighborCard(position.nextUnit(), skuCache)));
      }
    }

    units.sort(Comparator.comparingInt(OrderUnitResponse::sequenceNumber));

    return httpResponseFactory.ok(
        new OrderDetailResponse(
            orderItem.getOrderId(),
            orderItem.getStatus(),
            orderItem.getCreatedAt() != null ? orderItem.getCreatedAt().getEpochSecond() : 0,
            orderItem.getDeliveryMode(),
            orderItem.getBuyerName(),
            toBuyerAddress(orderItem.getBuyerAddress()),
            orderItem.getPostageOption(),
            orderItem.getTotalPrice(),
            OrderLines.itemsTotalPrice(orderLines),
            OrderLines.listedTotalPrice(orderLines),
            units.size(),
            lines,
            units));
  }

  private Map<Integer, List<UnitItem>> findBlockUnits(
      String user, List<OrderLines.OrderLine> orderLines) {
    var blocks = new TreeSet<Integer>();
    for (var line : orderLines) {
      for (var seqNum : line.allocatedSequenceNumbers()) {
        blocks.add(seqNum / 100);
      }
    }

    var gsi3 = unitTable.index(TcgInventoryTable.GSI3_NAME);
    var blockUnits = new HashMap<Integer, List<UnitItem>>();
    for (var block : blocks) {
      var request =
          QueryEnhancedRequest.builder()
              .queryConditional(
                  QueryConditional.sortBetween(
                      Key.builder()
                          .partitionValue(UnitItem.formatGsi3pk(user))
                          .sortValue(block * 100)
                          .build(),
                      Key.builder()
                          .partitionValue(UnitItem.formatGsi3pk(user))
                          .sortValue(block * 100 + 99)
                          .build()))
              .build();
      blockUnits.put(
          block, gsi3.query(request).stream().flatMap(page -> page.items().stream()).toList());
    }
    return blockUnits;
  }

  // current position and neighbors are a snapshot of the box at read time: sold and removed
  // units are gone, in-stock and reserved units still occupy their slots
  private BlockPosition computeBlockPosition(
      Map<Integer, List<UnitItem>> blockUnits, int sequenceNumber) {
    var offset = 0;
    UnitItem previous = null;
    UnitItem next = null;

    for (var unit : blockUnits.get(sequenceNumber / 100)) {
      int unitSeq = unit.getSequenceNumber();
      var status = unit.getStatus();
      if (unitSeq == sequenceNumber || "sold".equals(status) || "removed".equals(status)) {
        continue;
      }
      if (unitSeq < sequenceNumber) {
        offset++;
        if (previous == null || previous.getSequenceNumber() < unitSeq) {
          previous = unit;
        }
      } else if (next == null || next.getSequenceNumber() > unitSeq) {
        next = unit;
      }
    }

    return new BlockPosition(
        InventoryLocation.formatLocation(sequenceNumber / 100, offset), previous, next);
  }

  @Nullable
  private NeighborCardResponse toNeighborCard(
      @Nullable UnitItem unit, Map<String, SkuItem> skuCache) {
    if (unit == null) {
      return null;
    }
    var skuItem = getSku(unit.getPk(), skuCache);
    if (skuItem == null) {
      throw new IllegalStateException("sku record missing for unit " + unit.getSequenceNumber());
    }
    return new NeighborCardResponse(
        skuItem.getName(),
        skuItem.getSetCode(),
        skuItem.getCollectorNumber(),
        skuItem.getFinish(),
        skuItem.getCondition());
  }

  @Nullable
  private SkuItem getSku(String skuPk, Map<String, SkuItem> skuCache) {
    return skuCache.computeIfAbsent(
        skuPk,
        pk ->
            skuTable.getItem(
                Key.builder().partitionValue(pk).sortValue(SkuItem.formatSk()).build()));
  }

  @Nullable
  private static BuyerAddressResponse toBuyerAddress(@Nullable OrderItem.BuyerAddress address) {
    if (address == null) {
      return null;
    }
    return new BuyerAddressResponse(
        address.getLine1(),
        address.getLine2(),
        address.getSuburb(),
        address.getCity(),
        address.getPostCode(),
        address.getCountry());
  }

  @Nullable
  private static String perUnitPrice(OrderLines.OrderLine line) {
    if (line.price() == null) {
      return null;
    }
    return new BigDecimal(line.price())
        .divide(BigDecimal.valueOf(line.quantity()), 2, RoundingMode.HALF_UP)
        .toPlainString();
  }
}
