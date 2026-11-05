package com.jordansimsmith.tcginventory.orders.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record UpdateOrderSettingsRequest(
    @JsonProperty("track_orders_after") @Nullable Long trackOrdersAfter) {}
