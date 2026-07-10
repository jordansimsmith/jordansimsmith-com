package com.jordansimsmith.prompts;

import com.google.common.base.Verify;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClasspathPromptRegistry implements PromptRegistry {
  private final Map<String, String> prompts = new ConcurrentHashMap<>();

  @Override
  public String get(String name) {
    return prompts.computeIfAbsent(name, this::load);
  }

  private String load(String name) {
    try (var input = ClasspathPromptRegistry.class.getClassLoader().getResourceAsStream(name)) {
      Verify.verifyNotNull(input, "prompt resource not found: %s", name);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
