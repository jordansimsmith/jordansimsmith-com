package com.jordansimsmith.lib.notifications;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class NotificationTestModule {

  @Provides
  @Singleton
  public FakeNotificationPublisher fakeNotificationPublisher() {
    return new FakeNotificationPublisher();
  }

  @Provides
  @Singleton
  public NotificationPublisher notificationPublisher(
      FakeNotificationPublisher fakeNotificationPublisher) {
    return fakeNotificationPublisher;
  }
}
