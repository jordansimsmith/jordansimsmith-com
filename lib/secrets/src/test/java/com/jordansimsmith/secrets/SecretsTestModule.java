package com.jordansimsmith.secrets;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class SecretsTestModule {

  @Provides
  @Singleton
  public FakeSecrets fakeSecrets() {
    return new FakeSecrets();
  }

  @Provides
  @Singleton
  public Secrets secrets(FakeSecrets fakeSecrets) {
    return fakeSecrets;
  }
}
