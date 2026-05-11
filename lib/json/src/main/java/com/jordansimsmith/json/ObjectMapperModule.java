package com.jordansimsmith.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Module;
import dagger.Provides;

@Module
public class ObjectMapperModule {
  @Provides
  ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}
