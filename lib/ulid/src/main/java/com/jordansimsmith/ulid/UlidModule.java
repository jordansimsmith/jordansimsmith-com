package com.jordansimsmith.ulid;

import com.jordansimsmith.time.Clock;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class UlidModule {
  @Provides
  @Singleton
  UlidGenerator ulidGenerator(Clock clock) {
    return new DefaultUlidGenerator(clock);
  }
}
