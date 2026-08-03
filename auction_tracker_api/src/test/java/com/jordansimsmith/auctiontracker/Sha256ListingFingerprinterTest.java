package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

public class Sha256ListingFingerprinterTest {
  private final ListingFingerprinter fingerprinter = new Sha256ListingFingerprinter();

  @Test
  void createShouldMatchWhenListingIsIdentical() {
    // arrange
    var item =
        new TradeMeClient.TradeMeItem(
            "url",
            "Trident Z RGB",
            "Great condition",
            new BigDecimal("100"),
            new BigDecimal("150"));

    // act
    var fingerprint1 = fingerprinter.create(item);
    var fingerprint2 = fingerprinter.create(item);

    // assert
    assertThat(fingerprint1).isEqualTo(fingerprint2).hasSize(64);
  }

  @Test
  void createShouldDifferWhenTitleFormattingChanges() {
    // arrange
    var item1 =
        new TradeMeClient.TradeMeItem(
            "url",
            "Trident Z RGB",
            "Great condition",
            new BigDecimal("100"),
            new BigDecimal("150"));
    var item2 =
        new TradeMeClient.TradeMeItem(
            "url",
            "trident z rgb",
            "Great condition",
            new BigDecimal("100"),
            new BigDecimal("150"));

    // act
    var fingerprint1 = fingerprinter.create(item1);
    var fingerprint2 = fingerprinter.create(item2);

    // assert
    assertThat(fingerprint1).isNotEqualTo(fingerprint2);
  }

  @Test
  void createShouldDifferWhenDescriptionChanges() {
    // arrange
    var item1 =
        new TradeMeClient.TradeMeItem(
            "url",
            "Trident Z RGB",
            "Great condition",
            new BigDecimal("100"),
            new BigDecimal("150"));
    var item2 =
        new TradeMeClient.TradeMeItem(
            "url",
            "Trident Z RGB",
            "One stick is faulty",
            new BigDecimal("100"),
            new BigDecimal("150"));

    // act
    var fingerprint1 = fingerprinter.create(item1);
    var fingerprint2 = fingerprinter.create(item2);

    // assert
    assertThat(fingerprint1).isNotEqualTo(fingerprint2);
  }

  @Test
  void createShouldDifferWhenStartPriceChanges() {
    // arrange
    var item1 =
        new TradeMeClient.TradeMeItem(
            "url",
            "Trident Z RGB",
            "Great condition",
            new BigDecimal("100"),
            new BigDecimal("150"));
    var item2 =
        new TradeMeClient.TradeMeItem(
            "url", "Trident Z RGB", "Great condition", new BigDecimal("90"), new BigDecimal("150"));

    // act
    var fingerprint1 = fingerprinter.create(item1);
    var fingerprint2 = fingerprinter.create(item2);

    // assert
    assertThat(fingerprint1).isNotEqualTo(fingerprint2);
  }

  @Test
  void createShouldDifferWhenBuyNowPriceChanges() {
    // arrange
    var item1 =
        new TradeMeClient.TradeMeItem(
            "url",
            "Trident Z RGB",
            "Great condition",
            new BigDecimal("100"),
            new BigDecimal("150"));
    var item2 =
        new TradeMeClient.TradeMeItem(
            "url",
            "Trident Z RGB",
            "Great condition",
            new BigDecimal("100"),
            new BigDecimal("140"));

    // act
    var fingerprint1 = fingerprinter.create(item1);
    var fingerprint2 = fingerprinter.create(item2);

    // assert
    assertThat(fingerprint1).isNotEqualTo(fingerprint2);
  }

  @Test
  void createShouldNormalizePriceScale() {
    // arrange
    var item1 =
        new TradeMeClient.TradeMeItem(
            "url",
            "Trident Z RGB",
            "Great condition",
            new BigDecimal("100.0"),
            new BigDecimal("150.00"));
    var item2 =
        new TradeMeClient.TradeMeItem(
            "url",
            "Trident Z RGB",
            "Great condition",
            new BigDecimal("100"),
            new BigDecimal("150"));

    // act
    var fingerprint1 = fingerprinter.create(item1);
    var fingerprint2 = fingerprinter.create(item2);

    // assert
    assertThat(fingerprint1).isEqualTo(fingerprint2);
  }
}
