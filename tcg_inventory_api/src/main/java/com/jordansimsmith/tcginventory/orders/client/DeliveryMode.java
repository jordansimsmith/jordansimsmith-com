package com.jordansimsmith.tcginventory.orders.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum DeliveryMode {
  @JsonProperty("DELIVERY")
  DELIVERY,
  @JsonProperty("PICKUP")
  PICKUP
}
