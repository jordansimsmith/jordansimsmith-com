package com.jordansimsmith.tcginventory.catalog.server;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.jordansimsmith.tcginventory.catalog.client.ImageUrls;
import java.net.URLEncoder;

public class CardImageUrls {
  public static ImageUrls forSource(String externalSource, String externalId) {
    var encodedExternalId = URLEncoder.encode(externalId, UTF_8).replace("+", "%20");
    return switch (externalSource) {
      case "scryfall" -> {
        var imageUrl =
            "https://api.scryfall.com/cards/" + encodedExternalId + "?format=image&version=";
        yield new ImageUrls(imageUrl + "small", imageUrl + "normal");
      }
      case "tcgplayer" ->
          new ImageUrls(
              "https://tcgplayer-cdn.tcgplayer.com/product/" + encodedExternalId + "_200w.jpg",
              "https://tcgplayer-cdn.tcgplayer.com/product/"
                  + encodedExternalId
                  + "_in_1000x1000.jpg");
      default -> throw new IllegalStateException("no card image URLs for external source");
    };
  }
}
