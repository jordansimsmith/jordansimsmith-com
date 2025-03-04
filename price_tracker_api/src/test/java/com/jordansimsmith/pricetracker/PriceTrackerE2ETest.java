package com.jordansimsmith.pricetracker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class PriceTrackerE2ETest {

  @Container
  private final PriceTrackerContainer priceTrackerContainer = new PriceTrackerContainer();

  @Test
  void shouldStartContainer() {
    assertThat(priceTrackerContainer.isRunning()).as("Container should be running").isTrue();
  }
}
