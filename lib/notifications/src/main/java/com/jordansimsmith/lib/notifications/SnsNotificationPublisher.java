package com.jordansimsmith.lib.notifications;

import com.google.common.collect.Iterables;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.ListTopicsRequest;
import software.amazon.awssdk.services.sns.model.PublishRequest;

public class SnsNotificationPublisher implements NotificationPublisher {
  private final SnsClient snsClient;

  public SnsNotificationPublisher(SnsClient snsClient) {
    this.snsClient = snsClient;
  }

  @Override
  public void publish(String topic, String subject, String message) {
    var topicArn = getTopicArn(topic);
    var req = PublishRequest.builder().topicArn(topicArn).subject(subject).message(message).build();

    snsClient.publish(req);
  }

  private String getTopicArn(String topic) {
    var req = ListTopicsRequest.builder().build();
    var res = snsClient.listTopics(req);
    var topics = res.topics().stream().filter(t -> t.topicArn().endsWith(":" + topic)).toList();
    return Iterables.getOnlyElement(topics).topicArn();
  }
}
