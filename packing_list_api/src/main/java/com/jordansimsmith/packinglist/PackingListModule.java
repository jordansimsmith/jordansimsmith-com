package com.jordansimsmith.packinglist;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class PackingListModule {
  @Provides
  @Singleton
  public TemplatesFactory templatesFactory() {
    return new TemplatesFactoryImpl();
  }
}
