package com.jordansimsmith.tcginventory.orders.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record OrderSummaryRow(
    @JsonProperty("sku_id") String skuId, @JsonProperty("price") @Nullable String price) {}
