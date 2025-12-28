package com.jordansimsmith.packinglist;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TripItemStatus {
  UNPACKED("unpacked"),
  PACKED("packed"),
  PACK_JUST_IN_TIME("pack-just-in-time");

  private final String value;

  TripItemStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  public static TripItemStatus fromValue(String value) {
    for (var status : values()) {
      if (status.value.equals(value)) {
        return status;
      }
    }
    throw new IllegalArgumentException("Unknown status: " + value);
  }
}
