package com.jordansimsmith.notifications;

public interface NotificationPublisher {
  void publish(String topic, String subject, String message);
}
