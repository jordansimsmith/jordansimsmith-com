package com.jordansimsmith.lib.notifications;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

public class SnsNotificationPublisher implements NotificationPublisher {
  private final SnsClient snsClient;

  public SnsNotificationPublisher(SnsClient snsClient) {
    this.snsClient = snsClient;
  }

  @Override
  public void publish(String topic, String subject, String message) {
    var req = PublishRequest.builder().topicArn(topic).subject(subject).message(message).build();

    snsClient.publish(req);
  }
}
