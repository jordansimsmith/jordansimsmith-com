package com.jordansimsmith.auctiontracker;

import java.util.Set;

public class ExcludedSellerUsernameFactoryImpl implements ExcludedSellerUsernameFactory {
  private static final Set<String> EXCLUDED_SELLER_USERNAMES = Set.of("roseshade");

  @Override
  public Set<String> findExcludedSellerUsernames() {
    return EXCLUDED_SELLER_USERNAMES;
  }
}
