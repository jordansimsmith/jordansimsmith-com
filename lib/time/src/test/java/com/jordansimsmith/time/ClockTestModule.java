package com.jordansimsmith.time;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class ClockTestModule {
  @Provides
  @Singleton
  FakeClock fakeClock() {
    return new FakeClock();
  }

  @Provides
  @Singleton
  Clock clock(FakeClock fakeClock) {
    return fakeClock;
  }
}
