package com.jordansimsmith.auctiontracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
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

  @VisibleForTesting static final String MODEL = "gpt-5.4-mini";
  @VisibleForTesting static final String REASONING_EFFORT = "none";

  private static final List<String> CRITERIA =
      List.of(
          "mtg_cards",
          "bulk_scale",
          "not_basic_lands",
          "not_universes_beyond",
          "civilian_seller",
          "fixed_collection");

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
  public boolean judge(String promptName, String title, String description) {
    try {
      return doJudge(promptName, title, description);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private boolean doJudge(String promptName, String title, String description) throws Exception {
    var systemPrompt = promptRegistry.get(promptName);
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
                MODEL,
                REASONING_EFFORT,
                true,
                List.of(LlmMessage.system(systemPrompt), LlmMessage.user(userMessage))));

    var judgment = objectMapper.readTree(response.content());
    var pass = true;
    for (var criterion : CRITERIA) {
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
