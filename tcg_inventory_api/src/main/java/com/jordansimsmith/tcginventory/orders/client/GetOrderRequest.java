package com.jordansimsmith.tcginventory.orders.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetOrderRequest(@JsonProperty("order_id") String orderId) {}
