package com.jordansimsmith.tcginventory.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

public class TcgPlayerCatalogTest {
  private final TcgPlayerCatalog catalog = new TcgPlayerCatalog();

  @Test
  void getImageUrlsShouldBuildTcgplayerCdnUrls() {
    // act
    var imageUrls = catalog.getImageUrls("283917");

    // assert
    assertThat(imageUrls.small())
        .isEqualTo("https://tcgplayer-cdn.tcgplayer.com/product/283917_200w.jpg");
    assertThat(imageUrls.normal())
        .isEqualTo("https://tcgplayer-cdn.tcgplayer.com/product/283917_in_1000x1000.jpg");
  }

  @Test
  void getImageUrlsShouldRejectNonNumericProductIds() {
    // act / assert
    assertThatThrownBy(() -> catalog.getImageUrls("pokemon-283917"))
        .isInstanceOf(CatalogException.BadRequest.class)
        .hasMessage("TCGplayer external id must be numeric");
  }

  @Test
  void reviewMethodsShouldReportCatalogUnavailable() {
    // act / assert
    assertThatThrownBy(() -> catalog.getCard("283917"))
        .isInstanceOf(CatalogException.BadRequest.class)
        .hasMessage("catalog review is unavailable for game: pokemon");
    assertThatThrownBy(() -> catalog.findCards(List.of("283917")))
        .isInstanceOf(CatalogException.BadRequest.class)
        .hasMessage("catalog review is unavailable for game: pokemon");
    assertThatThrownBy(() -> catalog.findAlternatives("283917", "normal", null))
        .isInstanceOf(CatalogException.BadRequest.class)
        .hasMessage("catalog review is unavailable for game: pokemon");
    assertThatThrownBy(() -> catalog.search("Abomasnow", "normal", null))
        .isInstanceOf(CatalogException.BadRequest.class)
        .hasMessage("catalog review is unavailable for game: pokemon");
  }
}
