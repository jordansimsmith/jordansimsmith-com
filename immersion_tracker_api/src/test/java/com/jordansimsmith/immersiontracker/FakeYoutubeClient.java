package com.jordansimsmith.immersiontracker;

import com.google.common.base.Preconditions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class FakeYoutubeClient implements YoutubeClient {
  private final Map<String, Video> videos = new HashMap<>();
  private final Map<String, Channel> channels = new HashMap<>();

  @Override
  public Video getVideo(String videoId) {
    var video = videos.get(videoId);
    Preconditions.checkNotNull(video, "Video not found: %s", videoId);
    return video;
  }

  @Override
  public Channel getChannel(String channelId) {
    var channel = channels.get(channelId);
    Preconditions.checkNotNull(channel, "Channel not found: %s", channelId);
    return channel;
  }

  public void setVideo(
      String videoId, String title, String channelId, String channelTitle, Duration duration) {
    videos.put(videoId, new Video(videoId, title, channelId, channelTitle, duration));
  }

  public void setChannel(String channelId, String channelTitle, String artworkUrl) {
    channels.put(channelId, new Channel(channelId, channelTitle, artworkUrl));
  }
}
