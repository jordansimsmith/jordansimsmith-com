package com.jordansimsmith.immersiontracker;

import com.google.common.base.Preconditions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class FakeYoutubeClient implements YoutubeClient {
  private final Map<String, Video> videos = new HashMap<>();

  @Override
  public Video getVideo(String videoId) {
    var video = videos.get(videoId);
    Preconditions.checkNotNull(video, "Video not found: %s", videoId);
    return video;
  }

  public void setVideo(String videoId, String title, String channelId, Duration duration) {
    videos.put(videoId, new Video(videoId, title, channelId, duration));
  }
}
