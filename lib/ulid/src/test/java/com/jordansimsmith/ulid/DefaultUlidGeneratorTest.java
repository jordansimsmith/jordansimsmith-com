package com.jordansimsmith.ulid;

import static org.assertj.core.api.Assertions.assertThat;

import com.jordansimsmith.time.FakeClock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

public class DefaultUlidGeneratorTest {
  @Test
  void generateShouldReturn26CharacterString() {
    // arrange
    var clock = new FakeClock();
    var generator = new DefaultUlidGenerator(clock);

    // act
    var ulid = generator.generate();

    // assert
    assertThat(ulid).hasSize(26);
    assertThat(ulid).matches("[0-9A-HJKMNP-TV-Z]{26}");
  }

  @Test
  void generateShouldProduceUniqueValues() {
    // arrange
    var clock = new FakeClock();
    var generator = new DefaultUlidGenerator(clock);

    // act
    var ulid1 = generator.generate();
    var ulid2 = generator.generate();

    // assert
    assertThat(ulid1).isNotEqualTo(ulid2);
  }

  @Test
  void generateShouldSortChronologically() {
    // arrange
    var clock = new FakeClock();
    var generator = new DefaultUlidGenerator(clock);

    clock.setTime(Instant.ofEpochSecond(1700000000));
    var earlier = generator.generate();

    clock.setTime(Instant.ofEpochSecond(1700001000));
    var later = generator.generate();

    // assert
    assertThat(earlier).isLessThan(later);
  }
}
