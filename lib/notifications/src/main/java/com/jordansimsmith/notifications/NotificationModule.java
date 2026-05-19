package com.jordansimsmith.notifications;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.services.sns.SnsClient;

@Module
public class NotificationModule {
  @Provides
  @Singleton
  NotificationPublisher notificationPublisher() {
    var snsClient = SnsClient.builder().build();
    // prime the snapshot to optimise cold start times
    snsClient.listTopics();
    return new SnsNotificationPublisher(snsClient);
  }
}
