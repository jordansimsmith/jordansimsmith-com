package com.jordansimsmith.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.secrets.Secrets;
import com.jordansimsmith.secrets.SecretsModule;
import dagger.Component;
import javax.inject.Singleton;

@Singleton
@Component(modules = {SecretsModule.class, ObjectMapperModule.class})
public interface AuthFactory {
  Secrets secrets();

  ObjectMapper objectMapper();

  static AuthFactory create() {
    return DaggerAuthFactory.create();
  }
}
