package com.jordansimsmith.packinglist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.secrets.Secrets;
import com.jordansimsmith.secrets.SecretsModule;
import dagger.Component;
import javax.inject.Singleton;

@Singleton
@Component(modules = {ObjectMapperModule.class, SecretsModule.class, PackingListModule.class})
public interface PackingListFactory {
  ObjectMapper objectMapper();

  Secrets secrets();

  TemplatesFactory templatesFactory();

  static PackingListFactory create() {
    return DaggerPackingListFactory.create();
  }
}
