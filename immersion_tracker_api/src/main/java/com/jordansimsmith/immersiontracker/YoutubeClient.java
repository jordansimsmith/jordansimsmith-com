package com.jordansimsmith.immersiontracker;

import java.time.Duration;

public interface YoutubeClient {
  record Video(String id, String title, String channelId, Duration duration) {}

  Video getVideo(String videoId);
}
