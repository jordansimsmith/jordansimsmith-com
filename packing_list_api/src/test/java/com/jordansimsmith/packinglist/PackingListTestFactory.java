package com.jordansimsmith.packinglist;

import com.jordansimsmith.json.ObjectMapperModule;
import dagger.Component;
import javax.inject.Singleton;

@Singleton
@Component(modules = {ObjectMapperModule.class, PackingListTestModule.class})
public interface PackingListTestFactory extends PackingListFactory {
  FakeTemplatesFactory fakeTemplatesFactory();

  static PackingListTestFactory create() {
    return DaggerPackingListTestFactory.create();
  }
}
