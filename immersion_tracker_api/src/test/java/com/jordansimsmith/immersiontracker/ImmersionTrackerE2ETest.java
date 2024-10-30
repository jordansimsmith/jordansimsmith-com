package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class ImmersionTrackerE2ETest {

  @Container
  private final ImmersionTrackerContainer immersionTrackerContainer =
      new ImmersionTrackerContainer();

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Test
  void scriptShouldSyncEpisodes() throws Exception {
    // arrange
    var tmp = new File(System.getProperty("java.io.tmpdir"));

    var show1 = Path.of(tmp.getPath(), "show 1").toFile();
    show1.mkdir();
    var watched1 = Path.of(show1.getPath(), "watched").toFile();
    watched1.mkdir();
    var show1Episode1 = Path.of(watched1.getPath(), "episode 1.mkv").toFile();
    show1Episode1.createNewFile();
    var show1Episode2 = Path.of(watched1.getPath(), "episode 2.mkv").toFile();
    show1Episode2.createNewFile();

    var show2 = Path.of(tmp.getPath(), "show 2").toFile();
    show2.mkdir();
    var watched2 = Path.of(show2.getPath(), "watched").toFile();
    watched2.mkdir();
    var show2Episode1 = Path.of(watched2.getPath(), "episode 1.mkv").toFile();
    show2Episode1.createNewFile();

    var show3 = Path.of(tmp.getPath(), "show 3").toFile();
    show3.mkdir();
    var watched3 = Path.of(show3.getPath(), "watched").toFile();
    watched3.mkdir();
    var show3Episode1 = Path.of(show3.getPath(), "episode 1.mkv").toFile();
    show3Episode1.createNewFile();

    var show4 = Path.of(tmp.getPath(), "show 4").toFile();
    show4.mkdir();

    // act
    var script = Path.of("immersion_tracker_api/sync-episodes-script.py");
    var processBuilder = new ProcessBuilder(script.toAbsolutePath().toString());
    processBuilder.redirectErrorStream(false);
    processBuilder.directory(tmp);
    processBuilder
        .environment()
        .put("IMMERSION_TRACKER_API_URL", immersionTrackerContainer.getApiUrl().toString());
    var process = processBuilder.start();

    var input = process.getOutputStream();
    input.write("278157\n".getBytes());
    input.write("270065\n".getBytes());
    input.write("\n".getBytes());
    input.flush();

    // assert
    int exitCode = process.waitFor();
    assertThat(exitCode)
        .withFailMessage(
            () ->
                new BufferedReader(new InputStreamReader(process.getErrorStream()))
                        .lines()
                        .collect(Collectors.joining("\n"))
                    + "\n"
                    + immersionTrackerContainer.getLogs())
        .isEqualTo(0);

    var output =
        new BufferedReader(new InputStreamReader(process.getInputStream()))
            .lines()
            .collect(Collectors.joining("\n"));
    var expectedOutput =
        """
        Finding local episodes watched...
        Syncing 3 local episodes watched...
        Successfully added 3 new episodes to the remote server.
        Retrieving remote show metadata...
        Enter the TVDB id for show show 1:
        Successfully updated show metadata.
        Enter the TVDB id for show show 2:
        Successfully updated show metadata.
        Retrieving progress summary...

        2 episodes of ハイキュー!!
        1 episodes of Free!

        3 episodes watched today.
        1 total hours watched.

        Press ENTER to close...""";
    assertThat(output).isEqualTo(expectedOutput);
  }
}
