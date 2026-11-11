package com.jordansimsmith.tcginventory.imports.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ImportDecision {
  @JsonProperty("keep")
  KEEP,
  @JsonProperty("discard")
  DISCARD,
  @JsonProperty("review")
  REVIEW
}
