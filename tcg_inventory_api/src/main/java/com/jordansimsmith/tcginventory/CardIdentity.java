package com.jordansimsmith.tcginventory;

import java.util.regex.Pattern;

public record CardIdentity(String game, String externalSource, String externalId) {
  private static final Pattern TOKEN_PATTERN = Pattern.compile("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$");
  private static final Pattern EXTERNAL_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._~-]+$");

  public CardIdentity {
    if (game == null || !TOKEN_PATTERN.matcher(game).matches()) {
      throw new IllegalArgumentException("game must be a lower-snake-case token");
    }
    if (externalSource == null || !TOKEN_PATTERN.matcher(externalSource).matches()) {
      throw new IllegalArgumentException("external source must be a lower-snake-case token");
    }
    if (externalId == null || externalId.isBlank()) {
      throw new IllegalArgumentException("external id must not be blank");
    }
    if (!EXTERNAL_ID_PATTERN.matcher(externalId).matches()) {
      throw new IllegalArgumentException(
          "external id must contain only ASCII unreserved characters");
    }
  }
}
