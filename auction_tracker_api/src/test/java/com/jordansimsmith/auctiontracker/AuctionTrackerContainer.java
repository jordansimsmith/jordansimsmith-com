package com.jordansimsmith.auctiontracker;

import com.jordansimsmith.localstack.LocalStackContainer;

public class AuctionTrackerContainer extends LocalStackContainer<AuctionTrackerContainer> {
  public AuctionTrackerContainer() {
    super("test.properties", "auctiontracker.image.name", "auctiontracker.image.loader");
  }
}
