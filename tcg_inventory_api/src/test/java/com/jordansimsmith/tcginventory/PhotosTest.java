package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
    assertThat(Photos.needsPhotos("keep", "20.00", null)).isTrue();
    assertThat(Photos.needsPhotos("keep", "20", List.of())).isTrue();
    assertThat(Photos.needsPhotos("keep", "60.00", null)).isTrue();
  }

  @Test
  void needsPhotosShouldBeFalseBelowGateOrNonKeepOrWhenPhotosPresent() {
    // arrange
    var photos = List.of(TcgInventoryItem.Photo.create("01JEXAMPLEPHOTOULID00000", null));

    // act / assert
    assertThat(Photos.needsPhotos("keep", "19.99", null)).isFalse();
    assertThat(Photos.needsPhotos("discard", "20.00", null)).isFalse();
    assertThat(Photos.needsPhotos("review", "20.00", null)).isFalse();
    assertThat(Photos.needsPhotos("keep", null, null)).isFalse();
    assertThat(Photos.needsPhotos("keep", "20.00", photos)).isFalse();
  }
}
