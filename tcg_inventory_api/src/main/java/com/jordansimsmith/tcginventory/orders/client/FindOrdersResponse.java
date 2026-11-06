package com.jordansimsmith.tcginventory.orders.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record FindOrdersResponse(
    @JsonProperty("orders") List<OrderSummary> orders,
    @JsonProperty("next_continuation") @Nullable String nextContinuation) {}
