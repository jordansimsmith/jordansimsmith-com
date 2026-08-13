package com.jordansimsmith.ulid;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class UlidTestModule {
  @Provides
  @Singleton
  FakeUlidGenerator fakeUlidGenerator() {
    return new FakeUlidGenerator();
  }

  @Provides
  @Singleton
  UlidGenerator ulidGenerator(FakeUlidGenerator fakeUlidGenerator) {
    return fakeUlidGenerator;
  }
}
