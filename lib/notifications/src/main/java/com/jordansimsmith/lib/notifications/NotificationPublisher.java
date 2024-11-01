package com.jordansimsmith.lib.notifications;

public interface NotificationPublisher {
  void publish(String topic, String subject, String message);
}
