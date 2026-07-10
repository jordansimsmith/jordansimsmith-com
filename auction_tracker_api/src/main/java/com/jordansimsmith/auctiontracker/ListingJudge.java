package com.jordansimsmith.auctiontracker;

public interface ListingJudge {
  boolean judge(String promptName, String title, String description);
}
