package com.jordansimsmith.japanesedictionary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class RomajiNormaliserTest {
  private final RomajiNormaliser normaliser = new RomajiNormaliser();

  @Test
  void normaliseShouldLowercaseInput() {
    assertThat(normaliser.normalise("SHIN")).isEqualTo("shin");
  }

  @Test
  void normaliseShouldExpandMacronVowels() {
    assertThat(normaliser.normalise("Tōkyō")).isEqualTo("toukyou");
    assertThat(normaliser.normalise("kōhī")).isEqualTo("kouhii");
    assertThat(normaliser.normalise("ūmū")).isEqualTo("uumuu");
    assertThat(normaliser.normalise("ākā")).isEqualTo("aakaa");
    assertThat(normaliser.normalise("ēe")).isEqualTo("eee");
  }

  @Test
  void normaliseShouldExpandCircumflexVowels() {
    assertThat(normaliser.normalise("kôhi")).isEqualTo("kouhi");
    assertThat(normaliser.normalise("yûsha")).isEqualTo("yuusha");
  }

  @Test
  void normaliseShouldRewriteKunreiStandaloneConsonants() {
    assertThat(normaliser.normalise("si")).isEqualTo("shi");
    assertThat(normaliser.normalise("ti")).isEqualTo("chi");
    assertThat(normaliser.normalise("tu")).isEqualTo("tsu");
    assertThat(normaliser.normalise("hu")).isEqualTo("fu");
    assertThat(normaliser.normalise("zi")).isEqualTo("ji");
    assertThat(normaliser.normalise("di")).isEqualTo("ji");
    assertThat(normaliser.normalise("du")).isEqualTo("zu");
  }

  @Test
  void normaliseShouldRewriteKunreiDigraphs() {
    assertThat(normaliser.normalise("sya")).isEqualTo("sha");
    assertThat(normaliser.normalise("syu")).isEqualTo("shu");
    assertThat(normaliser.normalise("syo")).isEqualTo("sho");
    assertThat(normaliser.normalise("tya")).isEqualTo("cha");
    assertThat(normaliser.normalise("tyu")).isEqualTo("chu");
    assertThat(normaliser.normalise("tyo")).isEqualTo("cho");
    assertThat(normaliser.normalise("zya")).isEqualTo("ja");
    assertThat(normaliser.normalise("zyu")).isEqualTo("ju");
    assertThat(normaliser.normalise("zyo")).isEqualTo("jo");
  }

  @Test
  void normaliseShouldStripApostrophes() {
    assertThat(normaliser.normalise("n'a")).isEqualTo("na");
    assertThat(normaliser.normalise("on'aji")).isEqualTo("onaji");
  }

  @Test
  void normaliseShouldBeIdempotentOnAlreadyNormalisedInput() {
    var inputs =
        new String[] {"shi", "tsu", "chi", "fu", "ja", "shou", "tokyou", "shinbashi", "tsumi"};
    for (var input : inputs) {
      assertThat(normaliser.normalise(input)).as("idempotent for %s", input).isEqualTo(input);
      assertThat(normaliser.normalise(normaliser.normalise(input)))
          .as("idempotent after double pass for %s", input)
          .isEqualTo(input);
    }
  }

  @Test
  void normaliseShouldHandleHepburnInputUnchanged() {
    assertThat(normaliser.normalise("shin")).isEqualTo("shin");
    assertThat(normaliser.normalise("shinbashi")).isEqualTo("shinbashi");
  }

  @Test
  void normaliseShouldReturnNullForNullInput() {
    assertThat(normaliser.normalise(null)).isNull();
  }
}
