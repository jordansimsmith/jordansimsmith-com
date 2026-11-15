package com.jordansimsmith.secrets;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

@Module
public class SecretsModule {

  @Provides
  @Singleton
  Secrets secrets() {
    var secretsManager =
        SecretsManagerClient.builder()
            .region(Region.of(System.getenv("AWS_REGION")))
            .httpClientBuilder(AwsCrtHttpClient.builder())
            .build();
    return new SecretsManagerSecrets(secretsManager);
  }
}
