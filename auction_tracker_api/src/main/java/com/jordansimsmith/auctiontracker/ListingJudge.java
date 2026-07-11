package com.jordansimsmith.auctiontracker;

public interface ListingJudge {
  boolean judge(SearchFactory.Judge judge, String title, String description);
}
