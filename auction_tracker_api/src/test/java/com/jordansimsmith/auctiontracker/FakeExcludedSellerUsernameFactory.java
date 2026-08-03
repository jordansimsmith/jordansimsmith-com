package com.jordansimsmith.auctiontracker;

import java.util.HashSet;
import java.util.Set;

public class FakeExcludedSellerUsernameFactory implements ExcludedSellerUsernameFactory {
  private final Set<String> excludedSellerUsernames = new HashSet<>();

  @Override
  public Set<String> findExcludedSellerUsernames() {
    return Set.copyOf(excludedSellerUsernames);
  }

  public void addExcludedSellerUsernames(Set<String> usernames) {
    excludedSellerUsernames.addAll(usernames);
  }
}
