package com.jordansimsmith.packinglist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.json.ObjectMapperModule;
import dagger.Component;
import javax.inject.Singleton;

@Singleton
@Component(modules = {ObjectMapperModule.class, PackingListModule.class})
public interface PackingListFactory {
  ObjectMapper objectMapper();

  TemplatesFactory templatesFactory();

  static PackingListFactory create() {
    return DaggerPackingListFactory.create();
  }
}
