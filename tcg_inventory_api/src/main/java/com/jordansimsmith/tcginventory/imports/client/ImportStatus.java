package com.jordansimsmith.tcginventory.imports.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ImportStatus {
  @JsonProperty("appraising")
  APPRAISING,
  @JsonProperty("review")
  REVIEW,
  @JsonProperty("confirming")
  CONFIRMING,
  @JsonProperty("confirmed")
  CONFIRMED
}
