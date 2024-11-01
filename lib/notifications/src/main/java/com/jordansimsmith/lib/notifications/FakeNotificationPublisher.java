package com.jordansimsmith.lib.notifications;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import java.util.List;

public class FakeNotificationPublisher implements NotificationPublisher {
  private final Multimap<String, Notification> notifications = ArrayListMultimap.create();

  public record Notification(String subject, String message) {}

  @Override
  public void publish(String topic, String subject, String message) {
    notifications.put(topic, new Notification(subject, message));
  }

  public List<Notification> findNotifications(String topic) {
    return List.copyOf(notifications.get(topic));
  }

  public void reset() {
    notifications.clear();
  }
}
