package com.jordansimsmith.secrets;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

@Module
public class SecretsModule {

  @Provides
  @Singleton
  public Secrets secrets() {
    var secretsManger = SecretsManagerClient.builder().build();
    return new SecretsManagerSecrets(secretsManger);
  }
}
