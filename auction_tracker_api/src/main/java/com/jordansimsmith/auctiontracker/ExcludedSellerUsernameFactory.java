package com.jordansimsmith.auctiontracker;

import java.util.Set;

public interface ExcludedSellerUsernameFactory {
  Set<String> findExcludedSellerUsernames();
}
