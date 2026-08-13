package com.jordansimsmith.ulid;

import java.util.concurrent.atomic.AtomicInteger;

public class FakeUlidGenerator implements UlidGenerator {
  private final AtomicInteger counter = new AtomicInteger(0);

  @Override
  public String generate() {
    return "FAKE_ULID_%010d".formatted(counter.incrementAndGet());
  }

  public void reset() {
    counter.set(0);
  }
}
