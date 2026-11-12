package com.jordansimsmith.tcginventory.imports.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum Language {
  @JsonProperty("en")
  ENGLISH,
  @JsonProperty("ja")
  JAPANESE,
  @JsonProperty("other")
  OTHER
}
