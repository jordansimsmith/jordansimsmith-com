package com.jordansimsmith.notifications;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import java.util.List;

public class FakeNotificationPublisher implements NotificationPublisher {
  private final Multimap<String, Notification> notifications = ArrayListMultimap.create();
  private RuntimeException failure;

  public record Notification(String subject, String message) {}

  @Override
  public void publish(String topic, String subject, String message) {
    if (failure != null) {
      throw failure;
    }
    notifications.put(topic, new Notification(subject, message));
  }

  public void failWith(RuntimeException failure) {
    this.failure = failure;
  }

  public List<Notification> findNotifications(String topic) {
    return List.copyOf(notifications.get(topic));
  }

  public void reset() {
    notifications.clear();
    failure = null;
  }
}
