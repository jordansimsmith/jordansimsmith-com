package com.jordansimsmith.http;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class RequestContextModule {
  @Provides
  @Singleton
  public RequestContextFactory requestContextFactory() {
    return new RequestContextFactory();
  }
}
