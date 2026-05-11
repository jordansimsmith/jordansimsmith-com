package com.jordansimsmith.immersiontracker;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

public interface SpotifyClient {
  record EpisodeDetails(
      String id,
      String title,
      String showId,
      String showName,
      String showArtworkUrl,
      Duration duration,
      LocalDate releaseDate) {}

  record Episode(String id, String title, Duration duration, LocalDate releaseDate) {}

  EpisodeDetails getEpisode(String episodeId);

  List<Episode> findShowEpisodes(String showId);
}
