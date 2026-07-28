package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class AuctionTrackerItemTest {
  @Test
  void createFingerprintShouldMatchWhenContentIsIdentical() {
    // arrange
    var title = "Trident Z RGB";
    var description = "Great condition";

    // act
    var fingerprint1 = AuctionTrackerItem.createFingerprint(title, description);
    var fingerprint2 = AuctionTrackerItem.createFingerprint(title, description);

    // assert
    assertThat(fingerprint1).isEqualTo(fingerprint2).hasSize(64);
  }

  @Test
  void createFingerprintShouldDifferWhenFormattingChanges() {
    // arrange
    var description = "Great condition";

    // act
    var fingerprint1 = AuctionTrackerItem.createFingerprint("Trident Z RGB", description);
    var fingerprint2 = AuctionTrackerItem.createFingerprint("trident z rgb", description);

    // assert
    assertThat(fingerprint1).isNotEqualTo(fingerprint2);
  }

  @Test
  void createFingerprintShouldDifferWhenDescriptionChanges() {
    // arrange
    var title = "Trident Z RGB";

    // act
    var fingerprint1 = AuctionTrackerItem.createFingerprint(title, "Great condition");
    var fingerprint2 = AuctionTrackerItem.createFingerprint(title, "One stick is faulty");

    // assert
    assertThat(fingerprint1).isNotEqualTo(fingerprint2);
  }

  @Test
  void formatGsi2pkShouldPrefixFingerprint() {
    // arrange
    var fingerprint = AuctionTrackerItem.createFingerprint("Trident Z RGB", "Great condition");

    // act
    var gsi2pk = AuctionTrackerItem.formatGsi2pk(fingerprint);

    // assert
    assertThat(gsi2pk).isEqualTo("FINGERPRINT#" + fingerprint);
  }
}
