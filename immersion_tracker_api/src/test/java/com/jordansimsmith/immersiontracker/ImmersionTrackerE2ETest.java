package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class ImmersionTrackerE2ETest {

  @Container
  private final ImmersionTrackerContainer immersionTrackerContainer =
      new ImmersionTrackerContainer();

  @Test
  void test() {
    assertThat(immersionTrackerContainer.isRunning()).isTrue();
  }
}
