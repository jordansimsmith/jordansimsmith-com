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
  private static final SearchFactory.Judge JUDGE =
      new SearchFactory.Judge(
          "prompts/mtg-bulk-judge.md",
          "gpt-5.4-mini",
          "none",
          List.of(
              "mtg_cards",
              "bulk_scale",
              "not_basic_lands",
              "not_universes_beyond",
              "civilian_seller",
              "fixed_collection"));

  private static final SearchFactory.Judge RAM_JUDGE =
      new SearchFactory.Judge(
          "prompts/ram-judge.md",
          "gpt-5.4-nano",
          "low",
          List.of(
              "trident_z_family",
              "ddr4",
              "kit_2x16gb",
              "speed_3200",
              "timings_cl16",
              "desktop_udimm"));

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
    var pass = listingJudge.judge(JUDGE, "MTG bulk lot", "500 assorted cards");

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
    var pass = listingJudge.judge(JUDGE, "MTG bulk lot", "store repack, 1 rare guaranteed");

    // assert
    assertThat(pass).isFalse();
  }

  @Test
  void judgeShouldThrowWhenCriterionMissing() {
    // arrange
    fakeLlmClient.addResponse("{\"mtg_cards\": {\"reasoning\": \"ok\", \"result\": \"pass\"}}");

    // act & assert
    assertThatThrownBy(() -> listingJudge.judge(JUDGE, "MTG bulk lot", "500 cards"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("bulk_scale");
  }

  @Test
  void judgeShouldThrowWhenResultMalformed() {
    // arrange
    fakeLlmClient.addResponse(judgmentJson().replace("\"pass\"", "\"maybe\""));

    // act & assert
    assertThatThrownBy(() -> listingJudge.judge(JUDGE, "MTG bulk lot", "500 cards"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void judgeShouldUseRamJudgeConfiguration() {
    // arrange
    fakeLlmClient.addResponse(ramJudgmentJson());

    // act
    var pass =
        listingJudge.judge(
            RAM_JUDGE, "G.Skill Trident Z RGB 32GB", "2x16GB DDR4 3200MHz CL16-18-18-38");

    // assert
    assertThat(pass).isTrue();

    var requests = fakeLlmClient.findRequests();
    assertThat(requests).hasSize(1);
    var request = requests.get(0);
    assertThat(request.model()).isEqualTo("gpt-5.4-nano");
    assertThat(request.reasoningEffort()).isEqualTo("low");
    assertThat(request.jsonResponse()).isTrue();
    assertThat(request.messages().get(0).content())
        .startsWith("You judge Trade Me auction listings")
        .contains("G.Skill Trident Z (plain or RGB), DDR4, 2x16GB (32GB total)")
        .contains("## Examples");
  }

  @Test
  void judgeShouldReturnFailWhenRamCriterionFails() {
    // arrange
    fakeLlmClient.addResponse(ramJudgmentJson("speed_3200"));

    // act
    var pass = listingJudge.judge(RAM_JUDGE, "G.Skill Trident Z RGB 32GB", "2x16GB DDR4 3600MHz");

    // assert
    assertThat(pass).isFalse();
  }

  @Test
  void judgeShouldThrowWhenRamCriterionMissing() {
    // arrange
    fakeLlmClient.addResponse(judgmentJson());

    // act & assert
    assertThatThrownBy(() -> listingJudge.judge(RAM_JUDGE, "G.Skill Trident Z RGB 32GB", "2x16GB"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("trident_z_family");
  }

  private static String ramJudgmentJson(String... failingCriteria) {
    return judgmentJson(RAM_JUDGE.criteria(), List.of(failingCriteria));
  }

  private static String judgmentJson(String... failingCriteria) {
    return judgmentJson(JUDGE.criteria(), List.of(failingCriteria));
  }

  private static String judgmentJson(List<String> criteria, List<String> failing) {
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
