package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateCredentialsRequest(@JsonProperty("refresh_token") String refreshToken) {
  @Override
  public String toString() {
    return "UpdateCredentialsRequest[refreshToken=<redacted>]";
  }
}
