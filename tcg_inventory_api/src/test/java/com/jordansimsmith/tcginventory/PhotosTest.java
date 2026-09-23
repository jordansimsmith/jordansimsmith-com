package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

public class PhotosTest {
  @Test
  void keyShouldUseUserAndPhotoId() {
    // act / assert
    assertThat(Photos.key("jordan", "01JEXAMPLEPHOTOULID00000"))
        .isEqualTo("users/jordan/photos/01JEXAMPLEPHOTOULID00000.jpg");
  }

  @Test
  void needsPhotosShouldBeTrueForKeepAtGateWithNoPhotos() {
    // act / assert
    assertThat(Photos.needsPhotos("keep", "20.00", 0)).isTrue();
    assertThat(Photos.needsPhotos("keep", "20", 0)).isTrue();
    assertThat(Photos.needsPhotos("keep", "60.00", 0)).isTrue();
  }

  @Test
  void needsPhotosShouldBeFalseBelowGateOrNonKeepOrWhenPhotosPresent() {
    // arrange
    // act / assert
    assertThat(Photos.needsPhotos("keep", "19.99", 0)).isFalse();
    assertThat(Photos.needsPhotos("discard", "20.00", 0)).isFalse();
    assertThat(Photos.needsPhotos("review", "20.00", 0)).isFalse();
    assertThat(Photos.needsPhotos("keep", null, 0)).isFalse();
    assertThat(Photos.needsPhotos("keep", "20.00", 1)).isFalse();
  }

  @Test
  void needsPublishWarningShouldBeTrueAtOrAboveFiftyWhenPhotoLess() {
    // act / assert
    assertThat(Photos.needsPublishWarning(new BigDecimal("50"), 0)).isTrue();
    assertThat(Photos.needsPublishWarning(new BigDecimal("50.00"), 0)).isTrue();
    assertThat(Photos.needsPublishWarning(new BigDecimal("50.01"), 0)).isTrue();
  }

  @Test
  void needsPublishWarningShouldBeFalseBelowFiftyOrWhenPhotosPresent() {
    // arrange
    // act / assert
    assertThat(Photos.needsPublishWarning(new BigDecimal("49.99"), 0)).isFalse();
    assertThat(Photos.needsPublishWarning(new BigDecimal("60.00"), 1)).isFalse();
  }
}
