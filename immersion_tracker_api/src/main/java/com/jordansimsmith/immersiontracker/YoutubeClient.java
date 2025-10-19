package com.jordansimsmith.immersiontracker;

import java.time.Duration;

public interface YoutubeClient {
  record Video(String id, String title, String channelId, String channelTitle, Duration duration) {}

  Video getVideo(String videoId);
}
