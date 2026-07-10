package com.jordansimsmith.llm;

public interface LlmClient {
  LlmResponse complete(LlmRequest request);
}
