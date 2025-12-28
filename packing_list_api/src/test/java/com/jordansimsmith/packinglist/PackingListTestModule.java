package com.jordansimsmith.packinglist;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class PackingListTestModule {
  @Provides
  @Singleton
  public FakeTemplatesFactory fakeTemplatesFactory() {
    return new FakeTemplatesFactory();
  }

  @Provides
  @Singleton
  public TemplatesFactory templatesFactory(FakeTemplatesFactory fakeTemplatesFactory) {
    return fakeTemplatesFactory;
  }
}
