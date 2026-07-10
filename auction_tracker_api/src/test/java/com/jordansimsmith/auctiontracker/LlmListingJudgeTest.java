package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.llm.FakeLlmClient;
import com.jordansimsmith.llm.LlmMessage;
import com.jordansimsmith.prompts.ClasspathPromptRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class LlmListingJudgeTest {
  private static final String PROMPT = "prompts/mtg-bulk-judge.md";

  private FakeLlmClient fakeLlmClient;
  private LlmListingJudge listingJudge;

  @BeforeEach
  void setUp() {
    fakeLlmClient = new FakeLlmClient();
    listingJudge =
        new LlmListingJudge(new ClasspathPromptRegistry(), fakeLlmClient, new ObjectMapper());
  }

  @Test
  void judgeShouldReturnPassWhenAllCriteriaPass() {
    // arrange
    fakeLlmClient.addResponse(judgmentJson());

    // act
    var pass = listingJudge.judge(PROMPT, "MTG bulk lot", "500 assorted cards");

    // assert
    assertThat(pass).isTrue();

    var requests = fakeLlmClient.findRequests();
    assertThat(requests).hasSize(1);
    var request = requests.get(0);
    assertThat(request.model()).isEqualTo("gpt-5.4-mini");
    assertThat(request.reasoningEffort()).isEqualTo("none");
    assertThat(request.jsonResponse()).isTrue();
    assertThat(request.messages()).hasSize(2);
    assertThat(request.messages().get(0).role()).isEqualTo(LlmMessage.Role.SYSTEM);
    assertThat(request.messages().get(0).content())
        .startsWith("You judge Trade Me auction listings")
        .contains("## Examples");
    assertThat(request.messages().get(1).role()).isEqualTo(LlmMessage.Role.USER);
    assertThat(request.messages().get(1).content())
        .contains("Title: MTG bulk lot")
        .contains("Description: 500 assorted cards");
  }

  @Test
  void judgeShouldReturnFailWhenAnyCriterionFails() {
    // arrange
    fakeLlmClient.addResponse(judgmentJson("civilian_seller"));

    // act
    var pass = listingJudge.judge(PROMPT, "MTG bulk lot", "store repack, 1 rare guaranteed");

    // assert
    assertThat(pass).isFalse();
  }

  @Test
  void judgeShouldThrowWhenCriterionMissing() {
    // arrange
    fakeLlmClient.addResponse("{\"mtg_cards\": {\"reasoning\": \"ok\", \"result\": \"pass\"}}");

    // act & assert
    assertThatThrownBy(() -> listingJudge.judge(PROMPT, "MTG bulk lot", "500 cards"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("bulk_scale");
  }

  @Test
  void judgeShouldThrowWhenResultMalformed() {
    // arrange
    fakeLlmClient.addResponse(judgmentJson().replace("\"pass\"", "\"maybe\""));

    // act & assert
    assertThatThrownBy(() -> listingJudge.judge(PROMPT, "MTG bulk lot", "500 cards"))
        .isInstanceOf(RuntimeException.class);
  }

  private static String judgmentJson(String... failingCriteria) {
    var failing = List.of(failingCriteria);
    var criteria =
        List.of(
            "mtg_cards",
            "bulk_scale",
            "not_basic_lands",
            "not_universes_beyond",
            "civilian_seller",
            "fixed_collection");
    var builder = new StringBuilder("{");
    for (var i = 0; i < criteria.size(); i++) {
      var criterion = criteria.get(i);
      var result = failing.contains(criterion) ? "fail" : "pass";
      builder.append(
          "\"%s\": {\"reasoning\": \"because\", \"result\": \"%s\"}".formatted(criterion, result));
      if (i < criteria.size() - 1) {
        builder.append(",");
      }
    }
    return builder.append("}").toString();
  }
}
