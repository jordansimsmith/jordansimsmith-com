package com.jordansimsmith.llm;

public record LlmResponse(String content, long inputTokens, long outputTokens) {}
