package com.jordansimsmith.llm;

import com.google.common.base.Verify;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

public class FakeLlmClient implements LlmClient {
  private final Queue<LlmResponse> responses = new ArrayDeque<>();
  private final List<LlmRequest> requests = new ArrayList<>();

  @Override
  public LlmResponse complete(LlmRequest request) {
    requests.add(request);
    var response = responses.poll();
    Verify.verifyNotNull(response, "no queued llm response");
    return response;
  }

  public void addResponse(LlmResponse response) {
    responses.add(response);
  }

  public void addResponse(String content) {
    responses.add(new LlmResponse(content, 0, 0));
  }

  public List<LlmRequest> findRequests() {
    return List.copyOf(requests);
  }

  public void reset() {
    responses.clear();
    requests.clear();
  }
}
