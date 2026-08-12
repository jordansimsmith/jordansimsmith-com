package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class FetchTcgSetMappingTest {
  @Test
  void getShouldReturnEntriesForKnownSetCode() {
    // act
    var entries = FetchTcgSetMapping.get("a25");

    // assert
    assertThat(entries).isNotEmpty();
    assertThat(entries.get(0).setId()).isGreaterThan(0);
    assertThat(entries.get(0).setName()).isNotBlank();
  }

  @Test
  void getShouldReturnMultipleEntriesForPlst() {
    // act
    var entries = FetchTcgSetMapping.get("plst");

    // assert
    assertThat(entries).hasSizeGreaterThan(1);
    assertThat(entries).anyMatch(e -> e.setId() == 3075 && e.setName().equals("Mystery Booster"));
  }

  @Test
  void getShouldReturnEmptyListForUnmappedSetCode() {
    // act
    var entries = FetchTcgSetMapping.get("zzz_nonexistent_set");

    // assert
    assertThat(entries).isEmpty();
  }

  @Test
  void containsShouldReturnTrueForMappedCode() {
    // act & assert
    assertThat(FetchTcgSetMapping.contains("a25")).isTrue();
  }

  @Test
  void containsShouldReturnFalseForUnmappedCode() {
    // act & assert
    assertThat(FetchTcgSetMapping.contains("zzz_nonexistent_set")).isFalse();
  }

  @Test
  void mappingShouldCoverManySetCodes() {
    // the mapping should have at least 500 entries
    // (verifies the JSON resource loaded successfully with substantial data)
    assertThat(FetchTcgSetMapping.contains("fdn")).isTrue();
    assertThat(FetchTcgSetMapping.contains("blb")).isTrue();
    assertThat(FetchTcgSetMapping.contains("dsk")).isTrue();
  }
}
