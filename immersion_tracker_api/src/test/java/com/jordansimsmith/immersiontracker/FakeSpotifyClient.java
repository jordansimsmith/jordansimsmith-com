package com.jordansimsmith.immersiontracker;

import com.google.common.base.Preconditions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class FakeSpotifyClient implements SpotifyClient {
  private final Map<String, Episode> episodes = new HashMap<>();

  @Override
  public Episode getEpisode(String episodeId) {
    var episode = episodes.get(episodeId);
    Preconditions.checkNotNull(episode, "Episode not found: %s", episodeId);
    return episode;
  }

  public void setEpisode(
      String episodeId,
      String title,
      String showId,
      String showName,
      String showArtworkUrl,
      Duration duration) {
    episodes.put(
        episodeId, new Episode(episodeId, title, showId, showName, showArtworkUrl, duration));
  }
}
