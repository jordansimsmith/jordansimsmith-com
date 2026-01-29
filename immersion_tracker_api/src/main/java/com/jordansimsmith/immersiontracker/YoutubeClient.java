package com.jordansimsmith.immersiontracker;

import java.time.Duration;

public interface YoutubeClient {
  record Video(String id, String title, String channelId, String channelTitle, Duration duration) {}

  record Channel(String id, String title, String artworkUrl) {}

  Video getVideo(String videoId);

  Channel getChannel(String channelId);
}
