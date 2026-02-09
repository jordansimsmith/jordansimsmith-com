package com.jordansimsmith.secrets;

import com.google.common.base.Preconditions;
import java.util.HashMap;
import java.util.Map;

public class FakeSecrets implements Secrets {
  private final Map<String, String> secrets;

  public FakeSecrets() {
    this.secrets = new HashMap<>();
  }

  @Override
  public String get(String name) {
    var secret = secrets.get(name);
    Preconditions.checkNotNull(secret);
    return secret;
  }

  public void set(String name, String secret) {
    secrets.put(name, secret);
  }

  public void reset() {
    secrets.clear();
  }
}
