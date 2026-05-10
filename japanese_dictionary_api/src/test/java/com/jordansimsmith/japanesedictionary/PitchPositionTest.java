package com.jordansimsmith.japanesedictionary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class PitchPositionTest {
  private final PitchPosition pitchPosition = new PitchPosition();

  @Test
  void moraCountShouldCountStandaloneKana() {
    assertThat(pitchPosition.moraCount("ねこ")).isEqualTo(2);
    assertThat(pitchPosition.moraCount("しんばし")).isEqualTo(4);
  }

  @Test
  void moraCountShouldFuseSmallYaYuYoIntoPrecedingMora() {
    assertThat(pitchPosition.moraCount("じゅう")).isEqualTo(2);
    assertThat(pitchPosition.moraCount("ちょっと")).isEqualTo(3);
    assertThat(pitchPosition.moraCount("しゃ")).isEqualTo(1);
  }

  @Test
  void moraCountShouldCountSokuonAndCholonAndN() {
    assertThat(pitchPosition.moraCount("がっこう")).isEqualTo(4);
    assertThat(pitchPosition.moraCount("コーヒー")).isEqualTo(4);
    assertThat(pitchPosition.moraCount("ほん")).isEqualTo(2);
  }

  @Test
  void moraCountShouldHandleKatakanaSmallVowels() {
    assertThat(pitchPosition.moraCount("ジュース")).isEqualTo(3);
  }

  @Test
  void moraCountShouldReturnZeroForNullOrEmpty() {
    assertThat(pitchPosition.moraCount(null)).isZero();
    assertThat(pitchPosition.moraCount("")).isZero();
  }

  @Test
  void validateShouldAcceptZeroAndN() {
    assertThat(pitchPosition.validate(0, 4)).isTrue();
    assertThat(pitchPosition.validate(4, 4)).isTrue();
  }

  @Test
  void validateShouldAcceptInteriorPositions() {
    assertThat(pitchPosition.validate(1, 4)).isTrue();
    assertThat(pitchPosition.validate(2, 4)).isTrue();
    assertThat(pitchPosition.validate(3, 4)).isTrue();
  }

  @Test
  void validateShouldRejectNegativeOrTooLargePositions() {
    assertThat(pitchPosition.validate(-1, 4)).isFalse();
    assertThat(pitchPosition.validate(5, 4)).isFalse();
    assertThat(pitchPosition.validate(1314, 4)).isFalse();
  }
}
