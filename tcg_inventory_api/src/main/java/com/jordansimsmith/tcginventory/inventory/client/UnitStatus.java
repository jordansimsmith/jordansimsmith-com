package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum UnitStatus {
  @JsonProperty("in_stock")
  IN_STOCK,
  @JsonProperty("reserved")
  RESERVED,
  @JsonProperty("sold")
  SOLD,
  @JsonProperty("removed")
  REMOVED
}
