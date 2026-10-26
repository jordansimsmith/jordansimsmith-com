package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import javax.annotation.Nullable;

public record Offer(
    @JsonProperty("id") long id,
    @JsonProperty("status") @Nullable String status,
    @JsonProperty("current_action") @Nullable String currentAction,
    @JsonProperty("accepted_at") @Nullable String acceptedAt,
    @JsonProperty("delivery_mode") @Nullable String deliveryMode,
    @JsonProperty("buyer_name") @Nullable String buyerName,
    @JsonProperty("buyer_region_address") @Nullable BuyerAddress buyerAddress,
    @JsonProperty("shipping_option") @Nullable ShippingOption shippingOption,
    @JsonProperty("total_offer_price") @Nullable BigDecimal totalOfferPrice,
    @JsonProperty("items") @Nullable List<OfferItem> items) {}
