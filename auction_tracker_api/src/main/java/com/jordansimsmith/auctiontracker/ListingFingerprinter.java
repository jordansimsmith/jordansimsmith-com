package com.jordansimsmith.auctiontracker;

public interface ListingFingerprinter {
  String create(TradeMeClient.TradeMeItem item);
}
