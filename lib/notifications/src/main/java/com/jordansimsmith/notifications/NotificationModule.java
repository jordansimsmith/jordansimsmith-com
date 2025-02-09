package com.jordansimsmith.notifications;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.services.sns.SnsClient;

@Module
public class NotificationModule {
  @Provides
  @Singleton
  public NotificationPublisher notificationPublisher() {
    var snsClient = SnsClient.builder().build();
    return new SnsNotificationPublisher(snsClient);
  }
}
