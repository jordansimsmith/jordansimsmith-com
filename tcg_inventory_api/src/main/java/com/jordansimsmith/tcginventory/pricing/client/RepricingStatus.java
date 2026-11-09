package com.jordansimsmith.tcginventory.pricing.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum RepricingStatus {
  @JsonProperty("queued")
  QUEUED,
  @JsonProperty("running")
  RUNNING,
  @JsonProperty("succeeded")
  SUCCEEDED
}
