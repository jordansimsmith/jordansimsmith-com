package com.jordansimsmith.tcginventory.catalog.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.catalog.client.FindCardImagesRequest;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import java.util.List;
import org.junit.jupiter.api.Test;

public class FindCardImagesServiceTest {
  private final FindCardImagesService service = new FindCardImagesService(new FakeGamesService());

  @Test
  void findCardImagesShouldPreserveDistinctRequestOrder() throws Exception {
    // arrange
    var request =
        new FindCardImagesRequest("mtg", List.of("lightning bolt", "other", "lightning bolt"));

    // act
    var response = service.findCardImages(new RequestContext("jordan"), request);

    // assert
    assertThat(response.images().stream().map(image -> image.externalId()).toList())
        .containsExactly("lightning bolt", "other");
    assertThat(response.images().getFirst().imageUrls().small())
        .isEqualTo("https://api.scryfall.com/cards/lightning%20bolt?format=image&version=small");
  }

  @Test
  void findCardImagesShouldConstructTcgplayerUrlsForOpaqueIds() throws Exception {
    // arrange
    var request = new FindCardImagesRequest("pokemon", List.of("product/alpha"));

    // act
    var response = service.findCardImages(new RequestContext("jordan"), request);

    // assert
    assertThat(response.images().getFirst().imageUrls().small())
        .isEqualTo("https://tcgplayer-cdn.tcgplayer.com/product/product%2Falpha_200w.jpg");
    assertThat(response.images().getFirst().imageUrls().normal())
        .isEqualTo("https://tcgplayer-cdn.tcgplayer.com/product/product%2Falpha_in_1000x1000.jpg");
  }

  @Test
  void findCardImagesShouldRejectInvalidRequestsBeforeLookingUpGame() {
    // arrange
    var request = new FindCardImagesRequest("mtg", List.of(" "));

    // act / assert
    assertThatThrownBy(() -> service.findCardImages(new RequestContext("jordan"), request))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("external_ids must not contain blank values");
  }

  @Test
  void findCardImagesShouldPropagateUnknownGame() {
    // arrange
    var request = new FindCardImagesRequest("unknown", List.of("card-id"));

    // act / assert
    assertThatThrownBy(() -> service.findCardImages(new RequestContext("jordan"), request))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("unsupported game: unknown");
  }

  @Test
  void cardImageUrlsShouldRejectUnsupportedExternalSource() {
    assertThatThrownBy(() -> CardImageUrls.forSource("unknown", "card-id"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no card image URLs for external source");
  }
}
