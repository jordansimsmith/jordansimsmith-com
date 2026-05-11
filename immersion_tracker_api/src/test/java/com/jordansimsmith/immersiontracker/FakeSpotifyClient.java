package com.jordansimsmith.immersiontracker;

import com.google.common.base.Preconditions;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeSpotifyClient implements SpotifyClient {
  private final Map<String, EpisodeDetails> episodeDetails = new HashMap<>();
  private final Map<String, List<Episode>> showEpisodes = new HashMap<>();

  @Override
  public EpisodeDetails getEpisode(String episodeId) {
    var episode = episodeDetails.get(episodeId);
    Preconditions.checkNotNull(episode, "Episode not found: %s", episodeId);
    return episode;
  }

  @Override
  public List<Episode> findShowEpisodes(String showId) {
    var episodes = showEpisodes.get(showId);
    Preconditions.checkNotNull(episodes, "Show not found: %s", showId);
    return episodes;
  }

  public void setEpisodeDetails(
      String episodeId,
      String title,
      String showId,
      String showName,
      String showArtworkUrl,
      Duration duration,
      LocalDate releaseDate) {
    episodeDetails.put(
        episodeId,
        new EpisodeDetails(
            episodeId, title, showId, showName, showArtworkUrl, duration, releaseDate));
  }

  public void setShowEpisodes(String showId, List<Episode> episodes) {
    showEpisodes.put(showId, episodes);
  }
}
