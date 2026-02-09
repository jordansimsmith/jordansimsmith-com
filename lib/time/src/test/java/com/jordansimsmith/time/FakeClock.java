package com.jordansimsmith.time;

import java.time.Instant;

public class FakeClock implements Clock {
  private static final long DEFAULT_EPOCH_MILLI = 946638000000L; // 2000-01-01

  private long currentEpochMilli = DEFAULT_EPOCH_MILLI;

  @Override
  public Instant now() {
    return Instant.ofEpochMilli(currentEpochMilli);
  }

  public void setTime(Instant instant) {
    this.currentEpochMilli = instant.toEpochMilli();
  }

  public void reset() {
    currentEpochMilli = DEFAULT_EPOCH_MILLI;
  }
}
