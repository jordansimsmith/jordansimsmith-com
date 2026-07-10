package com.jordansimsmith.llm;

import java.util.List;
import javax.annotation.Nullable;

public record LlmRequest(
    String model,
    @Nullable String reasoningEffort,
    boolean jsonResponse,
    List<LlmMessage> messages) {}
