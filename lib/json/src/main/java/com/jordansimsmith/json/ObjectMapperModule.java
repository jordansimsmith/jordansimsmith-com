package com.jordansimsmith.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Module;
import dagger.Provides;
import java.util.Map;
import javax.inject.Singleton;

@Module
public class ObjectMapperModule {
  @Provides
  @Singleton
  ObjectMapper objectMapper() {
    var mapper = new ObjectMapper();
    // prime the snapshot to optimise cold start times
    mapper.valueToTree(Map.of());
    return mapper;
  }
}
