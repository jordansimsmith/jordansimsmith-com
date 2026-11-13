package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ScanStatus {
  @JsonProperty("uploading")
  UPLOADING,
  @JsonProperty("identifying")
  IDENTIFYING,
  @JsonProperty("reviewing")
  REVIEWING,
  @JsonProperty("confirming")
  CONFIRMING,
  @JsonProperty("confirmed")
  CONFIRMED
}
