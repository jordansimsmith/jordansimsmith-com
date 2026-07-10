package com.jordansimsmith.prompts;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class PromptsModule {

  @Provides
  @Singleton
  PromptRegistry promptRegistry() {
    return new ClasspathPromptRegistry();
  }
}
