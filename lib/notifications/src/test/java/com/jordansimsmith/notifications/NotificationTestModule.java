package com.jordansimsmith.notifications;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class NotificationTestModule {

  @Provides
  @Singleton
  FakeNotificationPublisher fakeNotificationPublisher() {
    return new FakeNotificationPublisher();
  }

  @Provides
  @Singleton
  NotificationPublisher notificationPublisher(FakeNotificationPublisher fakeNotificationPublisher) {
    return fakeNotificationPublisher;
  }
}
