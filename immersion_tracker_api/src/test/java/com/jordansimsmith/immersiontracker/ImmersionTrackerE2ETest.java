package com.jordansimsmith.immersiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.dynamodb.DynamoDbUtils;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Testcontainers
public class ImmersionTrackerE2ETest {

  private static final String NETWORK_NAME = "immersion-tracker-e2e";

  private static final Network NETWORK =
      Network.builder().createNetworkCmdModifier(cmd -> cmd.withName(NETWORK_NAME)).build();

  @Container
  private static final ImmersionTrackerTvdbStubContainer immersionTrackerTvdbStubContainer =
      new ImmersionTrackerTvdbStubContainer().withNetwork(NETWORK);

  @Container
  private static final ImmersionTrackerYoutubeStubContainer immersionTrackerYoutubeStubContainer =
      new ImmersionTrackerYoutubeStubContainer().withNetwork(NETWORK);

  @Container
  private static final ImmersionTrackerSpotifyAccountsStubContainer
      immersionTrackerSpotifyAccountsStubContainer =
          new ImmersionTrackerSpotifyAccountsStubContainer().withNetwork(NETWORK);

  @Container
  private static final ImmersionTrackerSpotifyApiStubContainer
      immersionTrackerSpotifyApiStubContainer =
          new ImmersionTrackerSpotifyApiStubContainer().withNetwork(NETWORK);

  @Container
  private static final ImmersionTrackerContainer immersionTrackerContainer =
      new ImmersionTrackerContainer()
          .withNetwork(NETWORK)
          .withEnv("LAMBDA_DOCKER_NETWORK", NETWORK_NAME)
          .withEnv(
              "IMMERSION_TRACKER_TVDB_BASE_URL",
              immersionTrackerTvdbStubContainer.getEndpoint().toString())
          .withEnv(
              "IMMERSION_TRACKER_YOUTUBE_BASE_URL",
              immersionTrackerYoutubeStubContainer.getEndpoint().toString())
          .withEnv(
              "IMMERSION_TRACKER_SPOTIFY_ACCOUNTS_BASE_URL",
              immersionTrackerSpotifyAccountsStubContainer.getEndpoint().toString())
          .withEnv(
              "IMMERSION_TRACKER_SPOTIFY_API_BASE_URL",
              immersionTrackerSpotifyApiStubContainer.getEndpoint().toString());

  private String formattedAllTimeProgressLabel;

  @BeforeEach
  void setup() {
    var dynamoDbClient =
        DynamoDbClient.builder()
            .endpointOverride(immersionTrackerContainer.getLocalstackUrl())
            .build();

    DynamoDbUtils.reset(dynamoDbClient);

    var today = LocalDate.now(ZoneId.of("Pacific/Auckland"));
    var zeroBasedMonth = today.getMonthValue() - 1;
    var quarterStartMonth = zeroBasedMonth - zeroBasedMonth % 3 + 1;
    var quarterStart = LocalDate.of(today.getYear(), quarterStartMonth, 1);
    formattedAllTimeProgressLabel =
        String.format(
            "%-11s", quarterStart.format(DateTimeFormatter.ofPattern("MMM uuuu", Locale.ENGLISH)));
  }

  @Test
  void scriptShouldSyncEpisodes() throws IOException, InterruptedException {
    // arrange
    var tmp = new File(System.getProperty("java.io.tmpdir"));

    var shows = Path.of(tmp.getPath(), "shows").toFile();
    Files.createDirectories(shows.toPath());

    var show1 = Path.of(shows.getPath(), "1 [123] Free!").toFile();
    Files.createDirectories(show1.toPath());
    var watched1 = Path.of(show1.getPath(), "watched").toFile();
    Files.createDirectories(watched1.toPath());
    var show1Episode1 = Path.of(watched1.getPath(), "[123] Free! episode 1.mp4").toFile();
    try (var show1Episode1RandomAccessFile = new RandomAccessFile(show1Episode1, "rw")) {
      show1Episode1RandomAccessFile.setLength(560 * 1024 * 1024);
    }
    var show1Episode2 = Path.of(watched1.getPath(), "[123] Free! episode 10.mp4").toFile();
    try (var show1Episode2RandomAccessFile = new RandomAccessFile(show1Episode2, "rw")) {
      show1Episode2RandomAccessFile.setLength(220 * 1024 * 1024);
    }
    var dsStore = Path.of(watched1.getPath(), ".DS_Store").toFile();
    Files.createFile(dsStore.toPath());

    var show2 = Path.of(shows.getPath(), "2 (123) Haikyuu Part 1").toFile();
    Files.createDirectories(show2.toPath());
    var watched2 = Path.of(show2.getPath(), "watched").toFile();
    Files.createDirectories(watched2.toPath());
    var show2Episode1 = Path.of(watched2.getPath(), "(123) Haikyuu S01E01.mkv").toFile();
    try (var show2Episode1RandomAccessFile = new RandomAccessFile(show2Episode1, "rw")) {
      show2Episode1RandomAccessFile.setLength(130 * 1024 * 1024);
    }

    var show3 = Path.of(shows.getPath(), "3 (123) Haikyuu Part 2").toFile();
    Files.createDirectories(show3.toPath());
    var watched3 = Path.of(show3.getPath(), "watched").toFile();
    Files.createDirectories(watched3.toPath());
    var show3Episode1 = Path.of(watched3.getPath(), "(123) Haikyuu S01E013.mkv").toFile();
    try (var show3Episode1RandomAccessFile = new RandomAccessFile(show3Episode1, "rw")) {
      show3Episode1RandomAccessFile.setLength(770 * 1024 * 1024);
    }
    var show3Episode2 = Path.of(show3.getPath(), "(123) Haikyuu S01E014.mkv").toFile();
    try (var show3Episode2RandomAccessFile = new RandomAccessFile(show3Episode2, "rw")) {
      show3Episode2RandomAccessFile.setLength(440 * 1024 * 1024);
    }

    var show4 = Path.of(shows.getPath(), "4 {123} Attack on Titan?").toFile();
    Files.createDirectories(show4.toPath());

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
        Finding local movies watched...
        Finding watched URLs...
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

        2 episodes of ハイキュー!!
        2 episodes of Free!

        4 episodes watched today.
        0 movies watched today.
        0 YouTube videos watched today.
        0 Spotify episodes watched today.

        Weekly activity:
        6 days ago │                                     0m
        5 days ago │                                     0m
        4 days ago │                                     0m
        3 days ago │                                     0m
        2 days ago │                                     0m
        Yesterday  │                                     0m
        Today      │██████████████████████████████   1h 36m

        All time progress:
        %s│██████████████████████████████       1h
        1 total hour watched.
        0 years and 0 months since immersion started.

        Deleting 4 local episodes watched...
        Deleted 1.64 GB of watched episodes.
        Deleted completed show: 1 [123] Free!
        Deleted completed show: 2 (123) Haikyuu Part 1

        Press ENTER to close...\
        """
            .formatted(formattedAllTimeProgressLabel);
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

  @Test
  void scriptShouldSyncMovies() throws IOException, InterruptedException {
    // arrange
    var tmp = new File(System.getProperty("java.io.tmpdir"));

    var movies = Path.of(tmp.getPath(), "movies").toFile();
    Files.createDirectories(movies.toPath());
    var watched = Path.of(movies.getPath(), "watched").toFile();
    Files.createDirectories(watched.toPath());
    var suzume = Path.of(watched.getPath(), "suzume.mp4").toFile();
    try (var suzumeRandomAccessFile = new RandomAccessFile(suzume, "rw")) {
      suzumeRandomAccessFile.setLength(1024L * 1024 * 1024);
    }
    var yourName = Path.of(watched.getPath(), "your_name.mkv").toFile();
    try (var yourNameRandomAccessFile = new RandomAccessFile(yourName, "rw")) {
      yourNameRandomAccessFile.setLength(1024L * 1024 * 1024 * 2);
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
    input.write("331904\n".getBytes());
    input.write("197\n".getBytes());
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
        Finding local movies watched...
        Finding watched URLs...
        Syncing 2 movies watched...
        Enter the TVDB id for movie suzume:
        Enter the TVDB id for movie your_name:
        Successfully added 2 new movies to the remote server.
        Retrieving progress summary...

        すずめの戸締まり
        君の名は。

        0 episodes watched today.
        2 movies watched today.
        0 YouTube videos watched today.
        0 Spotify episodes watched today.

        Weekly activity:
        6 days ago │                                     0m
        5 days ago │                                     0m
        4 days ago │                                     0m
        3 days ago │                                     0m
        2 days ago │                                     0m
        Yesterday  │                                     0m
        Today      │██████████████████████████████   3h 48m

        All time progress:
        %s│██████████████████████████████       3h
        3 total hours watched.
        0 years and 0 months since immersion started.

        Deleting 2 local movies watched...
        Deleted 3.00 GB of watched movies.

        Press ENTER to close...\
        """
            .formatted(formattedAllTimeProgressLabel);
    assertThat(output).isEqualTo(expectedOutput);
    assertThat(suzume).doesNotExist();
    assertThat(yourName).doesNotExist();
    assertThat(watched).exists();
    assertThat(movies).exists();
  }

  @Test
  void scriptShouldSyncYoutubeVideos() throws IOException, InterruptedException {
    // arrange
    var tmp = new File(System.getProperty("java.io.tmpdir"));

    var youtubeWatchedFile = Path.of(tmp.getPath(), "watched.txt").toFile();
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
        Finding local movies watched...
        Finding watched URLs...
        Syncing 2 YouTube videos watched...
        Successfully added 2 new YouTube videos to the remote server.
        Retrieving progress summary...

        1 video of LuisFonsiVEVO
        1 video of officialpsy

        0 episodes watched today.
        0 movies watched today.
        2 YouTube videos watched today.
        0 Spotify episodes watched today.

        Weekly activity:
        6 days ago │                                     0m
        5 days ago │                                     0m
        4 days ago │                                     0m
        3 days ago │                                     0m
        2 days ago │                                     0m
        Yesterday  │                                     0m
        Today      │██████████████████████████████       7m

        All time progress:
        %s│                                     0h
        0 total hours watched.
        0 years and 0 months since immersion started.

        Clearing watched URLs...

        Press ENTER to close...\
        """
            .formatted(formattedAllTimeProgressLabel);
    assertThat(output).isEqualTo(expectedOutput);

    assertThat(youtubeWatchedFile).exists();
    assertThat(youtubeWatchedFile.length()).isEqualTo(0);
  }

  @Test
  void scriptShouldSyncSpotifyEpisodes() throws IOException, InterruptedException {
    // arrange
    var tmp = new File(System.getProperty("java.io.tmpdir"));

    var spotifyWatchedFile = Path.of(tmp.getPath(), "watched.txt").toFile();
    try (var writer = new FileWriter(spotifyWatchedFile)) {
      writer.write("https://open.spotify.com/episode/5TmVVWd9TOCaF2bEtyYDwv?si=42fd92c2accd4ea9\n");
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
        Finding local movies watched...
        Finding watched URLs...
        Syncing 1 Spotify episodes watched...
        Successfully added 2 new Spotify episodes to the remote server.
        Retrieving progress summary...

        2 episodes of The Miku Real Japanese Podcast | Japanese conversation | Japanese culture

        0 episodes watched today.
        0 movies watched today.
        0 YouTube videos watched today.
        2 Spotify episodes watched today.

        Weekly activity:
        6 days ago │                                     0m
        5 days ago │                                     0m
        4 days ago │                                     0m
        3 days ago │                                     0m
        2 days ago │                                     0m
        Yesterday  │                                     0m
        Today      │██████████████████████████████      56m

        All time progress:
        %s│                                     0h
        0 total hours watched.
        0 years and 0 months since immersion started.

        Clearing watched URLs...

        Press ENTER to close...\
        """
            .formatted(formattedAllTimeProgressLabel);
    assertThat(output).isEqualTo(expectedOutput);

    assertThat(spotifyWatchedFile).exists();
    assertThat(spotifyWatchedFile.length()).isEqualTo(0);
  }
}
