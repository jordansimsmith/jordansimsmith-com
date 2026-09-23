package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

public class ImportRowsTest {
  @Test
  void totalSuggestedPriceShouldBeZeroWhenNoKeepRows() {
    // arrange
    var discard = row("discard", null);
    var review = row("review", null);

    // act / assert
    assertThat(ImportRows.totalSuggestedPrice(List.of())).isEqualTo("0.00");
    assertThat(ImportRows.totalSuggestedPrice(List.of(discard, review))).isEqualTo("0.00");
  }

  @Test
  void totalSuggestedPriceShouldSumKeepRowsOnly() {
    // arrange
    var keepA = row("keep", "1.50");
    var keepB = row("keep", "3.00");
    var discard = row("discard", null);
    var review = row("review", null);

    // act / assert
    assertThat(ImportRows.totalSuggestedPrice(List.of(keepA, discard, keepB, review)))
        .isEqualTo("4.50");
  }

  private static ImportRowItem row(String decision, String suggestedPrice) {
    var item = new ImportRowItem();
    item.setDecision(decision);
    item.setSuggestedPrice(suggestedPrice);
    return item;
  }
}
