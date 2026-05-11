package com.jordansimsmith.secrets;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class SecretsTestModule {

  @Provides
  @Singleton
  FakeSecrets fakeSecrets() {
    return new FakeSecrets();
  }

  @Provides
  @Singleton
  Secrets secrets(FakeSecrets fakeSecrets) {
    return fakeSecrets;
  }
}
