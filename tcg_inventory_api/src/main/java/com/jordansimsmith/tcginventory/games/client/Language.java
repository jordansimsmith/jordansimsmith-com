package com.jordansimsmith.tcginventory.games.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum Language {
  @JsonProperty("en")
  ENGLISH("en"),
  @JsonProperty("ja")
  JAPANESE("ja");

  private final String code;

  Language(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
