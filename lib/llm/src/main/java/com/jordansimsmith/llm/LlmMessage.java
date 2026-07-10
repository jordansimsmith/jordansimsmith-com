package com.jordansimsmith.llm;

public record LlmMessage(Role role, String content) {
  public enum Role {
    SYSTEM,
    USER
  }

  public static LlmMessage system(String content) {
    return new LlmMessage(Role.SYSTEM, content);
  }

  public static LlmMessage user(String content) {
    return new LlmMessage(Role.USER, content);
  }
}
