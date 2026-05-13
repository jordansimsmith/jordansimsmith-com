package com.jordansimsmith.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.secrets.Secrets;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class AuthModule {

  @Provides
  @Singleton
  RequestAuthorizer requestAuthorizer(Secrets secrets, ObjectMapper objectMapper) {
    return new RequestAuthorizer(secrets, objectMapper);
  }
}
