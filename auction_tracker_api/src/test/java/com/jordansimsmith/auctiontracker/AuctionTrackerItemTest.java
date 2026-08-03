package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

public class AuctionTrackerItemTest {
  @Test
  void createFingerprintShouldMatchWhenContentIsIdentical() {
    // arrange
    var title = "Trident Z RGB";
    var description = "Great condition";

    // act
    var fingerprint1 =
        AuctionTrackerItem.createFingerprint(
            title, description, new BigDecimal("100"), new BigDecimal("150"));
    var fingerprint2 =
        AuctionTrackerItem.createFingerprint(
            title, description, new BigDecimal("100"), new BigDecimal("150"));

    // assert
    assertThat(fingerprint1).isEqualTo(fingerprint2).hasSize(64);
  }

  @Test
  void createFingerprintShouldDifferWhenFormattingChanges() {
    // arrange
    var description = "Great condition";

    // act
    var fingerprint1 =
        AuctionTrackerItem.createFingerprint(
            "Trident Z RGB", description, new BigDecimal("100"), new BigDecimal("150"));
    var fingerprint2 =
        AuctionTrackerItem.createFingerprint(
            "trident z rgb", description, new BigDecimal("100"), new BigDecimal("150"));

    // assert
    assertThat(fingerprint1).isNotEqualTo(fingerprint2);
  }

  @Test
  void createFingerprintShouldDifferWhenDescriptionChanges() {
    // arrange
    var title = "Trident Z RGB";

    // act
    var fingerprint1 =
        AuctionTrackerItem.createFingerprint(
            title, "Great condition", new BigDecimal("100"), new BigDecimal("150"));
    var fingerprint2 =
        AuctionTrackerItem.createFingerprint(
            title, "One stick is faulty", new BigDecimal("100"), new BigDecimal("150"));

    // assert
    assertThat(fingerprint1).isNotEqualTo(fingerprint2);
  }

  @Test
  void createFingerprintShouldDifferWhenStartPriceChanges() {
    // arrange
    var title = "Trident Z RGB";
    var description = "Great condition";

    // act
    var fingerprint1 =
        AuctionTrackerItem.createFingerprint(
            title, description, new BigDecimal("100"), new BigDecimal("150"));
    var fingerprint2 =
        AuctionTrackerItem.createFingerprint(
            title, description, new BigDecimal("90"), new BigDecimal("150"));

    // assert
    assertThat(fingerprint1).isNotEqualTo(fingerprint2);
  }

  @Test
  void createFingerprintShouldDifferWhenBuyNowPriceChanges() {
    // arrange
    var title = "Trident Z RGB";
    var description = "Great condition";

    // act
    var fingerprint1 =
        AuctionTrackerItem.createFingerprint(
            title, description, new BigDecimal("100"), new BigDecimal("150"));
    var fingerprint2 =
        AuctionTrackerItem.createFingerprint(
            title, description, new BigDecimal("100"), new BigDecimal("140"));

    // assert
    assertThat(fingerprint1).isNotEqualTo(fingerprint2);
  }

  @Test
  void createFingerprintShouldNormalizePriceScale() {
    // arrange
    var title = "Trident Z RGB";
    var description = "Great condition";

    // act
    var fingerprint1 =
        AuctionTrackerItem.createFingerprint(
            title, description, new BigDecimal("100.0"), new BigDecimal("150.00"));
    var fingerprint2 =
        AuctionTrackerItem.createFingerprint(
            title, description, new BigDecimal("100"), new BigDecimal("150"));

    // assert
    assertThat(fingerprint1).isEqualTo(fingerprint2);
  }

  @Test
  void formatGsi2pkShouldPrefixFingerprint() {
    // arrange
    var fingerprint =
        AuctionTrackerItem.createFingerprint(
            "Trident Z RGB", "Great condition", new BigDecimal("100"), null);

    // act
    var gsi2pk = AuctionTrackerItem.formatGsi2pk(fingerprint);

    // assert
    assertThat(gsi2pk).isEqualTo("FINGERPRINT#" + fingerprint);
  }
}
