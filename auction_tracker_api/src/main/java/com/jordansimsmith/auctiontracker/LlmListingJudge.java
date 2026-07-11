package com.jordansimsmith.auctiontracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Verify;
import com.jordansimsmith.llm.LlmClient;
import com.jordansimsmith.llm.LlmMessage;
import com.jordansimsmith.llm.LlmRequest;
import com.jordansimsmith.prompts.PromptRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LlmListingJudge implements ListingJudge {
  private static final Logger LOGGER = LoggerFactory.getLogger(LlmListingJudge.class);

  private final PromptRegistry promptRegistry;
  private final LlmClient llmClient;
  private final ObjectMapper objectMapper;

  public LlmListingJudge(
      PromptRegistry promptRegistry, LlmClient llmClient, ObjectMapper objectMapper) {
    this.promptRegistry = promptRegistry;
    this.llmClient = llmClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean judge(SearchFactory.Judge judge, String title, String description) {
    try {
      return doJudge(judge, title, description);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private boolean doJudge(SearchFactory.Judge judge, String title, String description)
      throws Exception {
    var systemPrompt = promptRegistry.get(judge.prompt());
    var userMessage =
        "Judge this listing. Respond with the JSON object described in your instructions.\n\n"
            + "Title: "
            + title
            + "\n"
            + "Description: "
            + description;

    var response =
        llmClient.complete(
            new LlmRequest(
                judge.model(),
                judge.reasoningEffort(),
                true,
                List.of(LlmMessage.system(systemPrompt), LlmMessage.user(userMessage))));

    var judgment = objectMapper.readTree(response.content());
    var pass = true;
    for (var criterion : judge.criteria()) {
      var value = judgment.get(criterion);
      Verify.verifyNotNull(value, "missing judgment for criterion %s", criterion);
      var result = value.path("result").asText(null);
      Verify.verify(
          "pass".equals(result) || "fail".equals(result),
          "malformed judgment result for criterion %s: %s",
          criterion,
          result);
      if ("fail".equals(result)) {
        LOGGER.info(
            "listing '{}' failed criterion {}: {}",
            title,
            criterion,
            value.path("reasoning").asText(""));
        pass = false;
      }
    }
    return pass;
  }
}
