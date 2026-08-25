package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class PhotosTest {
  @Test
  void keyShouldUseUserAndPhotoId() {
    // act / assert
    assertThat(Photos.key("jordan", "01JEXAMPLEPHOTOULID00000"))
        .isEqualTo("users/jordan/photos/01JEXAMPLEPHOTOULID00000.jpg");
  }
}
