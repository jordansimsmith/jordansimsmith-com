package com.jordansimsmith.tcginventory.orders.server;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FulfillmentMessage(
    @JsonProperty("user") String user, @JsonProperty("order_id") String orderId) {}
