package com.jordansimsmith.tcginventory.publish.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum PublishStatus {
  @JsonProperty("queued")
  QUEUED,
  @JsonProperty("running")
  RUNNING,
  @JsonProperty("succeeded")
  SUCCEEDED
}
