package com.jordansimsmith.llm;

import com.jordansimsmith.http.HttpStubContainer;

public class OpenAiStubContainer extends HttpStubContainer<OpenAiStubContainer> {
  public OpenAiStubContainer() {
    super(
        "openai-stub.properties",
        "openaistub.image.name",
        "openaistub.image.loader",
        "/opt/code/openai-stub/openai-stub-server_deploy.jar",
        "com.jordansimsmith.llm.OpenAiStubServer",
        "/health",
        "openai-stub");
  }

  public OpenAiStubContainer withResponseContent(String content) {
    return withEnv("OPENAI_STUB_RESPONSE_CONTENT", content);
  }
}
