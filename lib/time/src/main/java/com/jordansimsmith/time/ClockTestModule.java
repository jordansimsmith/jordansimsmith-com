package com.jordansimsmith.time;

import dagger.Module;
import dagger.Provides;

@Module
public class ClockTestModule {
  @Provides
  public FakeClock fakeClock() {
    return new FakeClock();
  }

  @Provides
  public Clock clock(FakeClock fakeClock) {
    return fakeClock;
  }
}
