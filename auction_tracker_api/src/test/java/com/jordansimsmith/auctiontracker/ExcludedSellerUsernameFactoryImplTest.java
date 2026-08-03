package com.jordansimsmith.auctiontracker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class ExcludedSellerUsernameFactoryImplTest {
  @Test
  void findExcludedSellerUsernamesShouldReturnConfiguredUsernames() {
    // arrange
    var factory = new ExcludedSellerUsernameFactoryImpl();

    // act
    var usernames = factory.findExcludedSellerUsernames();

    // assert
    assertThat(usernames).containsExactly("roseshade");
  }
}
