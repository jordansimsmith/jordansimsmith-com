package com.jordansimsmith.auctiontracker;

import static com.google.common.hash.Hashing.sha256;
import static java.nio.charset.StandardCharsets.UTF_8;

public final class Sha256ListingFingerprinter implements ListingFingerprinter {
  @Override
  public String create(TradeMeClient.TradeMeItem item) {
    var normalizedStartPrice = item.startPrice().stripTrailingZeros().toPlainString();
    var normalizedBuyNowPrice =
        item.buyNowPrice() == null ? "" : item.buyNowPrice().stripTrailingZeros().toPlainString();
    return sha256()
        .hashString(
            item.title()
                + "\0"
                + item.description()
                + "\0"
                + normalizedStartPrice
                + "\0"
                + normalizedBuyNowPrice,
            UTF_8)
        .toString();
  }
}
