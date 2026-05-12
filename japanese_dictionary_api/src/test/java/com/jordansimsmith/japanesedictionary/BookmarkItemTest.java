package com.jordansimsmith.japanesedictionary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

public class BookmarkItemTest {

  @Test
  void formatPkShouldUseUserPrefix() {
    assertThat(BookmarkItem.formatPk("alice")).isEqualTo("USER#alice");
  }

  @Test
  void formatSkShouldUseBookmarkPrefix() {
    assertThat(BookmarkItem.formatSk(1316830L)).isEqualTo("BOOKMARK#1316830");
  }

  @Test
  void createShouldPopulateAllAttributes() {
    var createdAt = Instant.ofEpochSecond(1731974400L);
    var item = BookmarkItem.create("alice", 1316830L, createdAt);

    assertThat(item.getPk()).isEqualTo("USER#alice");
    assertThat(item.getSk()).isEqualTo("BOOKMARK#1316830");
    assertThat(item.getUser()).isEqualTo("alice");
    assertThat(item.getSequence()).isEqualTo(1316830L);
    assertThat(item.getCreatedAt()).isEqualTo(createdAt);
  }

  @Test
  void equalsShouldRoundTripIdenticalItems() {
    var createdAt = Instant.ofEpochSecond(1700000000L);
    var a = BookmarkItem.create("bob", 42L, createdAt);
    var b = BookmarkItem.create("bob", 42L, createdAt);

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }
}
