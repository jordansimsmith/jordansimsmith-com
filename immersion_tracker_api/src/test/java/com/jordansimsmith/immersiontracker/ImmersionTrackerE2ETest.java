package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
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

  @Test
  void shouldStartContainer() {
    assertThat(immersionTrackerContainer.isRunning()).isTrue();
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Test
  void scriptShouldSyncEpisodes() throws Exception {
    // arrange
    var tmp = new File(System.getProperty("java.io.tmpdir"));

    var show1 = Path.of(tmp.getPath(), "1 [123] Free!").toFile();
    show1.mkdir();
    var watched1 = Path.of(show1.getPath(), "watched").toFile();
    watched1.mkdir();
    var show1Episode1 = Path.of(watched1.getPath(), "[123] Free! episode 1.mp4").toFile();
    show1Episode1.createNewFile();
    try (var show1Episode1RandomAccessFile = new RandomAccessFile(show1Episode1, "rw")) {
      show1Episode1RandomAccessFile.setLength(560 * 1024 * 1024);
    }
    var show1Episode2 = Path.of(watched1.getPath(), "[123] Free! episode 10.mp4").toFile();
    show1Episode2.createNewFile();
    try (var show1Episode2RandomAccessFile = new RandomAccessFile(show1Episode2, "rw")) {
      show1Episode2RandomAccessFile.setLength(220 * 1024 * 1024);
    }
    var dsStore = Path.of(watched1.getPath(), ".DS_Store").toFile();
    dsStore.createNewFile();

    var show2 = Path.of(tmp.getPath(), "2 (123) Haikyuu Part 1").toFile();
    show2.mkdir();
    var watched2 = Path.of(show2.getPath(), "watched").toFile();
    watched2.mkdir();
    var show2Episode1 = Path.of(watched2.getPath(), "(123) Haikyuu S01E01.mkv").toFile();
    show2Episode1.createNewFile();
    try (var show2Episode1RandomAccessFile = new RandomAccessFile(show2Episode1, "rw")) {
      show2Episode1RandomAccessFile.setLength(130 * 1024 * 1024);
    }

    var show3 = Path.of(tmp.getPath(), "3 (123) Haikyuu Part 2").toFile();
    show3.mkdir();
    var watched3 = Path.of(show3.getPath(), "watched").toFile();
    watched3.mkdir();
    var show3Episode1 = Path.of(watched3.getPath(), "(123) Haikyuu S01E013.mkv").toFile();
    show3Episode1.createNewFile();
    try (var show3Episode1RandomAccessFile = new RandomAccessFile(show3Episode1, "rw")) {
      show3Episode1RandomAccessFile.setLength(770 * 1024 * 1024);
    }
    var show3Episode2 = Path.of(show3.getPath(), "(123) Haikyuu S01E014.mkv").toFile();
    show3Episode2.createNewFile();
    try (var show3Episode2RandomAccessFile = new RandomAccessFile(show3Episode2, "rw")) {
      show3Episode2RandomAccessFile.setLength(440 * 1024 * 1024);
    }

    var show4 = Path.of(tmp.getPath(), "4 {123} Attack on Titan?").toFile();
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
        Finding YouTube videos watched...
        Syncing 4 local episodes watched...
        Successfully added 4 new episodes to the remote server.
        Retrieving remote show metadata...
        Enter the TVDB id for show 1 [123] Free!:
        Successfully updated show metadata.
        Enter the TVDB id for show 2 (123) Haikyuu Part 1:
        Successfully updated show metadata.
        Enter the TVDB id for show 3 (123) Haikyuu Part 2:
        Successfully updated show metadata.
        Retrieving progress summary...

        2 episodes of Free!
        2 episodes of ハイキュー!!

        4 episodes watched today.
        0 YouTube videos watched today.

        1 total hour watched.
        0 years and 0 months since immersion started.

        Deleting 4 local episodes watched...
        Deleted 1.64 GB of watched episodes.
        Deleted completed show: 1 [123] Free!
        Deleted completed show: 2 (123) Haikyuu Part 1

        Press ENTER to close...""";
    assertThat(output).isEqualTo(expectedOutput);
    assertThat(show1Episode1).doesNotExist();
    assertThat(show1Episode2).doesNotExist();
    assertThat(show2Episode1).doesNotExist();
    assertThat(show3Episode1).doesNotExist();
    assertThat(show3Episode2).exists();

    assertThat(show1).doesNotExist();
    assertThat(show2).doesNotExist();
    assertThat(show3).exists();
    assertThat(show4).exists();
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Test
  void scriptShouldSyncYoutubeVideos() throws Exception {
    // arrange
    var tmp = new File(System.getProperty("java.io.tmpdir"));

    var youtubeWatchedFile = Path.of(tmp.getPath(), "youtube_watched.txt").toFile();
    youtubeWatchedFile.createNewFile();
    try (var writer = new FileWriter(youtubeWatchedFile)) {
      writer.write("https://www.youtube.com/watch?v=9bZkp7q19f0\n");
      writer.write("https://www.youtube.com/watch?v=kJQP7kiw5Fk&t=30s\n");
    }

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
    input.write("\n".getBytes()); // Press ENTER to close
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
        Finding YouTube videos watched...
        Syncing 2 YouTube videos watched...
        Successfully added 2 new YouTube videos to the remote server.
        Retrieving progress summary...

        1 video of LuisFonsiVEVO
        1 video of officialpsy

        0 episodes watched today.
        2 YouTube videos watched today.

        0 total hours watched.
        0 years and 0 months since immersion started.

        Clearing YouTube videos watched...

        Press ENTER to close...""";
    assertThat(output).isEqualTo(expectedOutput);

    assertThat(youtubeWatchedFile).exists();
    assertThat(youtubeWatchedFile.length()).isEqualTo(0);
  }
}
