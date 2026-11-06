package com.jordansimsmith.tcginventory.orders.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record GetOrderResponse(
    @JsonProperty("order_id") String orderId,
    @JsonProperty("state") OrderState state,
    @JsonProperty("accepted_at") long acceptedAt,
    @JsonProperty("delivery_mode") @Nullable DeliveryMode deliveryMode,
    @JsonProperty("buyer_name") @Nullable String buyerName,
    @JsonProperty("buyer_address") @Nullable BuyerAddress buyerAddress,
    @JsonProperty("postage_option") @Nullable String postageOption,
    @JsonProperty("total_price") @Nullable String totalPrice,
    @JsonProperty("items_total_price") @Nullable String itemsTotalPrice,
    @JsonProperty("listed_total_price") @Nullable String listedTotalPrice,
    @JsonProperty("unit_count") int unitCount,
    @JsonProperty("lines") List<OrderLine> lines,
    @JsonProperty("units") List<OrderUnit> units) {}
