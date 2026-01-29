package com.jordansimsmith.immersiontracker;

import java.time.Duration;

public interface SpotifyClient {
  record Episode(
      String id,
      String title,
      String showId,
      String showName,
      String showArtworkUrl,
      Duration duration) {}

  Episode getEpisode(String episodeId);
}
