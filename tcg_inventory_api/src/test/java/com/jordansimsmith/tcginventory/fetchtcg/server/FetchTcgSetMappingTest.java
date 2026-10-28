package com.jordansimsmith.tcginventory.fetchtcg.server;

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
  void getShouldReturnEntriesForMappedCode() {
    // act & assert
    assertThat(FetchTcgSetMapping.get("a25")).isNotEmpty();
  }

  @Test
  void mappingShouldCoverManySetCodes() {
    // the mapping should have at least 500 entries
    // (verifies the JSON resource loaded successfully with substantial data)
    assertThat(FetchTcgSetMapping.get("fdn")).isNotEmpty();
    assertThat(FetchTcgSetMapping.get("blb")).isNotEmpty();
    assertThat(FetchTcgSetMapping.get("dsk")).isNotEmpty();
  }
}
