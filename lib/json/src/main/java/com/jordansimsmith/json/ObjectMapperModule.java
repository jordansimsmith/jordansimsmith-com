package com.jordansimsmith.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class ObjectMapperModule {
  @Provides
  @Singleton
  ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}
