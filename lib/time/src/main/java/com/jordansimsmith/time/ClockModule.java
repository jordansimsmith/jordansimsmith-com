package com.jordansimsmith.time;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class ClockModule {
  @Provides
  @Singleton
  public Clock clock() {
    return new SystemClock();
  }
}
