package com.jordansimsmith.tcginventory.orders.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum OrderState {
  @JsonProperty("reserving")
  RESERVING,
  @JsonProperty("awaiting_payment")
  AWAITING_PAYMENT,
  @JsonProperty("to_pick")
  TO_PICK,
  @JsonProperty("fulfilling")
  FULFILLING,
  @JsonProperty("fulfilled")
  FULFILLED,
  @JsonProperty("voiding")
  VOIDING,
  @JsonProperty("voided")
  VOIDED
}
