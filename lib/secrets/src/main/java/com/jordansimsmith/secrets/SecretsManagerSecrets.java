package com.jordansimsmith.secrets;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

public class SecretsManagerSecrets implements Secrets {
  private final SecretsManagerClient secretsClient;

  public SecretsManagerSecrets(SecretsManagerClient secretsClient) {
    this.secretsClient = secretsClient;
  }

  @Override
  public String get(String name) {
    var req = GetSecretValueRequest.builder().secretId(name).build();
    var res = secretsClient.getSecretValue(req);
    return res.secretString();
  }
}
