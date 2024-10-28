package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class ImmersionTrackerE2ETest {

  @Container
  private final ImmersionTrackerContainer immersionTrackerContainer =
      new ImmersionTrackerContainer();

  @Test
  void scriptShouldSyncEpisodes() throws Exception {
    assertThat(immersionTrackerContainer.isRunning()).isTrue();

    var processBuilder = new ProcessBuilder("immersion_tracker_api/sync-episodes-script.py");
    processBuilder.redirectErrorStream(true);

    var process = processBuilder.start();
    var input = process.getOutputStream();
    input.write("73\n".getBytes());
    input.flush();
    int exitCode = process.waitFor();
    assertThat(exitCode).isEqualTo(0);

    var outputReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    var output = outputReader.lines().collect(Collectors.joining("\n"));
    assertThat(output)
        .isEqualTo("""
        Hello, world!
        Pick a number:
        You picked 73""");
  }
}
