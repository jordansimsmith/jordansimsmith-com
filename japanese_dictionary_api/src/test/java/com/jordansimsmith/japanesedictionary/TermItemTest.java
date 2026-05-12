package com.jordansimsmith.japanesedictionary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class TermItemTest {

  @Test
  void formatPkAndSkShouldUseTermPrefix() {
    assertThat(TermItem.formatPk(1316830L)).isEqualTo("TERM#1316830");
    assertThat(TermItem.formatSk(1316830L)).isEqualTo("TERM#1316830");
  }

  @Test
  void formatGsiKeysShouldReturnRawIndexedValues() {
    assertThat(TermItem.formatGsi1pk()).isEqualTo("EXPRESSION");
    assertThat(TermItem.formatGsi1sk("新橋")).isEqualTo("新橋");
    assertThat(TermItem.formatGsi2pk()).isEqualTo("READING");
    assertThat(TermItem.formatGsi2sk("しんばし")).isEqualTo("しんばし");
    assertThat(TermItem.formatGsi3pk()).isEqualTo("ROMAJI");
    assertThat(TermItem.formatGsi3sk("shinbashi")).isEqualTo("shinbashi");
  }

  @Test
  void createShouldPopulateAllRequiredAttributes() {
    var item = TermItem.create(1316830L, "新橋", "しんばし", "shinbashi", 18472, 0, "{\"tag\":\"div\"}");

    assertThat(item.getPk()).isEqualTo("TERM#1316830");
    assertThat(item.getSk()).isEqualTo("TERM#1316830");
    assertThat(item.getGsi1pk()).isEqualTo("EXPRESSION");
    assertThat(item.getGsi1sk()).isEqualTo("新橋");
    assertThat(item.getGsi2pk()).isEqualTo("READING");
    assertThat(item.getGsi2sk()).isEqualTo("しんばし");
    assertThat(item.getGsi3pk()).isEqualTo("ROMAJI");
    assertThat(item.getGsi3sk()).isEqualTo("shinbashi");
    assertThat(item.getSequence()).isEqualTo(1316830L);
    assertThat(item.getExpression()).isEqualTo("新橋");
    assertThat(item.getReading()).isEqualTo("しんばし");
    assertThat(item.getReadingRomaji()).isEqualTo("shinbashi");
    assertThat(item.getFrequencyRank()).isEqualTo(18472);
    assertThat(item.getPitch()).isZero();
    assertThat(item.getGlossaryRaw()).isEqualTo("{\"tag\":\"div\"}");
  }

  @Test
  void createShouldAllowNullableFrequencyAndPitch() {
    var item = TermItem.create(1234L, "範疇文法", "はんちゅうぶんぽう", "hanchuubunpou", null, null, "{}");

    assertThat(item.getFrequencyRank()).isNull();
    assertThat(item.getPitch()).isNull();
    assertThat(item.getGlossaryRaw()).isEqualTo("{}");
  }

  @Test
  void equalsShouldRoundTripIdenticalItems() {
    var a = TermItem.create(1L, "猫", "ねこ", "neko", 100, 2, "{}");
    var b = TermItem.create(1L, "猫", "ねこ", "neko", 100, 2, "{}");

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }
}
