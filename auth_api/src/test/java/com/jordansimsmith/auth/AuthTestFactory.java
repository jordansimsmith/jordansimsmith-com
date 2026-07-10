package com.jordansimsmith.auth;

import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.secrets.FakeSecrets;
import com.jordansimsmith.secrets.SecretsTestModule;
import dagger.Component;
import javax.inject.Singleton;

@Singleton
@Component(modules = {SecretsTestModule.class, ObjectMapperModule.class})
public interface AuthTestFactory extends AuthFactory {
  FakeSecrets fakeSecrets();

  static AuthTestFactory create() {
    return DaggerAuthTestFactory.create();
  }
}
