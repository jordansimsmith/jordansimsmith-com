package com.jordansimsmith.packinglist;

import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.secrets.FakeSecrets;
import com.jordansimsmith.secrets.SecretsTestModule;
import dagger.Component;
import javax.inject.Singleton;

@Singleton
@Component(
    modules = {ObjectMapperModule.class, SecretsTestModule.class, PackingListTestModule.class})
public interface PackingListTestFactory extends PackingListFactory {
  FakeSecrets fakeSecrets();

  FakeTemplatesFactory fakeTemplatesFactory();

  static PackingListTestFactory create() {
    return DaggerPackingListTestFactory.create();
  }
}
