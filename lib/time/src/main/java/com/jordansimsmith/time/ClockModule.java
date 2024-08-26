package com.jordansimsmith.time;

import dagger.Module;
import dagger.Provides;

@Module
public class ClockModule {
  @Provides
  public Clock clock() {
    return new SystemClock();
  }
}
