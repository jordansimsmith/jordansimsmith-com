package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
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
    try (var show1Episode1RandomAccessFile = new RandomAccessFile(show1Episode1, "rw")) {
      show1Episode1RandomAccessFile.setLength(560 * 1024 * 1024);
    }
    var show1Episode2 = Path.of(watched1.getPath(), "episode 2.mkv").toFile();
    show1Episode2.createNewFile();
    try (var show1Episode2RandomAccessFile = new RandomAccessFile(show1Episode2, "rw")) {
      show1Episode2RandomAccessFile.setLength(220 * 1024 * 1024);
    }

    var show2 = Path.of(tmp.getPath(), "show 2").toFile();
    show2.mkdir();
    var watched2 = Path.of(show2.getPath(), "watched").toFile();
    watched2.mkdir();
    var show2Episode1 = Path.of(watched2.getPath(), "episode 1.mkv").toFile();
    show2Episode1.createNewFile();
    try (var show2Episode1RandomAccessFile = new RandomAccessFile(show2Episode1, "rw")) {
      show2Episode1RandomAccessFile.setLength(130 * 1024 * 1024);
    }

    var show3 = Path.of(tmp.getPath(), "show 3").toFile();
    show3.mkdir();
    var watched3 = Path.of(show3.getPath(), "watched").toFile();
    watched3.mkdir();
    var show3Episode1 = Path.of(watched3.getPath(), "episode 1.mkv").toFile();
    show3Episode1.createNewFile();
    try (var show3Episode1RandomAccessFile = new RandomAccessFile(show3Episode1, "rw")) {
      show3Episode1RandomAccessFile.setLength(770 * 1024 * 1024);
    }

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
        Syncing 4 local episodes watched...
        Successfully added 4 new episodes to the remote server.
        Retrieving remote show metadata...
        Enter the TVDB id for show show 1:
        Successfully updated show metadata.
        Enter the TVDB id for show show 2:
        Successfully updated show metadata.
        Enter the TVDB id for show show 3:
        Successfully updated show metadata.
        Retrieving progress summary...

        2 episodes of Free!
        2 episodes of ハイキュー!!

        4 episodes watched today.
        1 total hours watched.

        Deleting 4 local episodes watched...
        Deleted 1.64 GB of watched episodes.

        Press ENTER to close...""";
    assertThat(output).isEqualTo(expectedOutput);
    assertThat(show1Episode1).doesNotExist();
    assertThat(show1Episode2).doesNotExist();
    assertThat(show2Episode1).doesNotExist();
    assertThat(show3Episode1).doesNotExist();
  }
}
