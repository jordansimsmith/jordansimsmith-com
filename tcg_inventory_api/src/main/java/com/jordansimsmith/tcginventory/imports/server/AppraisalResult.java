package com.jordansimsmith.tcginventory.imports.server;

import javax.annotation.Nullable;

public record AppraisalResult(
    Decision decision, @Nullable String notes, @Nullable String cardId, @Nullable Integer setId) {
  public enum Decision {
    RESOLVED,
    REVIEW
  }
}
