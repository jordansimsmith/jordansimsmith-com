package com.jordansimsmith.tcginventory.imports.server;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ImportMessageType {
  @JsonProperty("appraise")
  APPRAISE,
  @JsonProperty("confirm")
  CONFIRM
}
