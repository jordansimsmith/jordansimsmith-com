package com.jordansimsmith.tcginventory.orders.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.Continuations;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.orders.client.DeliveryMode;
import com.jordansimsmith.tcginventory.orders.client.FindOrdersRequest;
import com.jordansimsmith.tcginventory.orders.client.FindOrdersResponse;
import com.jordansimsmith.tcginventory.orders.client.OrderState;
import com.jordansimsmith.tcginventory.orders.client.OrderSummary;
import com.jordansimsmith.tcginventory.orders.client.OrderSummaryRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class FindOrdersService {
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private final OrderReadRepository orderReadRepository;
  private final ObjectMapper objectMapper;

  public FindOrdersService(OrderReadRepository orderReadRepository, ObjectMapper objectMapper) {
    this.orderReadRepository = orderReadRepository;
    this.objectMapper = objectMapper;
  }

  public FindOrdersResponse findOrders(RequestContext ctx, FindOrdersRequest request)
      throws ServiceException {
    var limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit must be between 1 and 100");
    }

    var continuation = decodeContinuation(request.continuation());
    var page = orderReadRepository.findOrders(ctx.user(), limit, continuation);
    if (page == null) {
      return new FindOrdersResponse(List.of(), null);
    }

    var summaries = new ArrayList<OrderSummary>();
    for (var order : page.items()) {
      var rows = orderReadRepository.findRows(ctx.user(), order);
      summaries.add(toSummary(order, rows));
    }
    return new FindOrdersResponse(
        summaries, Continuations.encode(page.lastEvaluatedKey(), objectMapper));
  }

  @Nullable
  private Map<String, AttributeValue> decodeContinuation(@Nullable String token)
      throws BadRequestException {
    if (token == null || token.isEmpty()) {
      return null;
    }
    try {
      return Continuations.decode(token, objectMapper);
    } catch (RuntimeException e) {
      throw new BadRequestException("Invalid continuation", e);
    }
  }

  private static OrderSummary toSummary(OrderItem order, List<OrderRowItem> rows) {
    var unitCount = rows.stream().mapToInt(OrderRowItem::getQuantity).sum();
    return new OrderSummary(
        order.getOrderId(),
        OrderState.valueOf(order.getStatus().toUpperCase(Locale.ROOT)),
        order.getCreatedAt() == null ? 0 : order.getCreatedAt().getEpochSecond(),
        toDeliveryMode(order.getDeliveryMode()),
        order.getTotalPrice(),
        OrderRows.itemsTotalPrice(rows),
        OrderRows.listedTotalPrice(rows),
        unitCount,
        rows.stream().map(FindOrdersService::toSummaryRow).toList());
  }

  private static OrderSummaryRow toSummaryRow(OrderRowItem row) {
    return new OrderSummaryRow(row.getSkuId(), row.getPrice());
  }

  @Nullable
  private static DeliveryMode toDeliveryMode(@Nullable String value) {
    return value == null ? null : DeliveryMode.valueOf(value);
  }
}
