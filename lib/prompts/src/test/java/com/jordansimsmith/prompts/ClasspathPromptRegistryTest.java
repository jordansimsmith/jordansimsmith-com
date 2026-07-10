package com.jordansimsmith.prompts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ClasspathPromptRegistryTest {
  private ClasspathPromptRegistry promptRegistry;

  @BeforeEach
  void setUp() {
    promptRegistry = new ClasspathPromptRegistry();
  }

  @Test
  void getShouldReturnPromptContentWhenResourceExists() {
    // act
    var prompt = promptRegistry.get("prompts/example.md");

    // assert
    assertThat(prompt).isEqualTo("You are a helpful assistant.\n\nRespond with a JSON object.\n");
  }

  @Test
  void getShouldThrowWhenResourceDoesNotExist() {
    // act & assert
    assertThatThrownBy(() -> promptRegistry.get("prompts/missing.md"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("prompts/missing.md");
  }

  @Test
  void getShouldReturnCachedContentWhenCalledAgain() {
    // arrange
    var first = promptRegistry.get("prompts/example.md");

    // act
    var second = promptRegistry.get("prompts/example.md");

    // assert
    assertThat(second).isSameAs(first);
  }
}
