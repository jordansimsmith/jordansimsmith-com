package com.jordansimsmith.tcginventory.reports.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ReportGenerationStatus {
  @JsonProperty("queued")
  QUEUED,

  @JsonProperty("running")
  RUNNING,

  @JsonProperty("succeeded")
  SUCCEEDED
}
