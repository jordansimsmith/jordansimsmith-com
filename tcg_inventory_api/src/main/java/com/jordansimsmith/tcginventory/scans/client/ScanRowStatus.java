package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ScanRowStatus {
  @JsonProperty("suggested")
  SUGGESTED,
  @JsonProperty("needs_review")
  NEEDS_REVIEW
}
