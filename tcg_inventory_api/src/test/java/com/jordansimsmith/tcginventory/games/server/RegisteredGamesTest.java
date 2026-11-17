package com.jordansimsmith.tcginventory.games.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.games.client.FindGamesRequest;
import com.jordansimsmith.tcginventory.games.client.Finish;
import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import com.jordansimsmith.tcginventory.games.client.Language;
import com.jordansimsmith.tcginventory.games.client.ScanReviewImageRegion;
import org.junit.jupiter.api.Test;

public class RegisteredGamesTest {
  @Test
  void findGamesShouldReturnOrderedRegisteredMetadata() throws Exception {
    var response =
        new FindGamesService().findGames(new RequestContext("user"), new FindGamesRequest());

    assertThat(response.games())
        .containsExactly(
            RegisteredGames.MAGIC_THE_GATHERING,
            RegisteredGames.POKEMON_ENGLISH,
            RegisteredGames.POKEMON_JAPANESE);
    assertThat(response.games().get(0).externalSource()).isEqualTo("scryfall");
    assertThat(response.games())
        .extracting(Game::language)
        .contains(Language.ENGLISH, Language.JAPANESE);
    assertThat(RegisteredGames.POKEMON_ENGLISH.scanningEnabled()).isTrue();
    assertThat(RegisteredGames.POKEMON_ENGLISH.csvImportEnabled()).isFalse();
    assertThat(RegisteredGames.POKEMON_JAPANESE.scanningEnabled()).isTrue();
    assertThat(RegisteredGames.POKEMON_JAPANESE.csvImportEnabled()).isFalse();
    assertThat(RegisteredGames.POKEMON_JAPANESE.finishes())
        .containsExactly(new Finish("normal", "Normal"), new Finish("holofoil", "Holofoil"));
    assertThat(RegisteredGames.POKEMON_JAPANESE.scanReviewImageRegions())
        .containsExactly(
            new ScanReviewImageRegion("set_and_number", "Set and number", 0, 0.85, 0.5, 0.15),
            new ScanReviewImageRegion(
                "artwork_stamps_left", "Left artwork stamps", 0, 0.3, 0.48, 0.28),
            new ScanReviewImageRegion(
                "artwork_stamps_right", "Right artwork stamps", 0.52, 0.3, 0.48, 0.28),
            new ScanReviewImageRegion(
                "lower_right_stamp_and_foil",
                "Lower-right stamp and foil",
                0.42,
                0.54,
                0.58,
                0.35));
  }

  @Test
  void getGameShouldReturnSameMetadataRegardlessOfRequestUser() throws Exception {
    var service = new GetGameService();

    assertThat(service.getGame(new RequestContext("alice"), new GetGameRequest("mtg")))
        .isEqualTo(service.getGame(new RequestContext("bob"), new GetGameRequest("mtg")));
  }

  @Test
  void getGameShouldRejectBlankAndUnknownIds() {
    var service = new GetGameService();

    assertThatThrownBy(() -> service.getGame(new RequestContext("user"), new GetGameRequest(" ")))
        .hasMessage("game_id is required");
    assertThatThrownBy(
            () -> service.getGame(new RequestContext("user"), new GetGameRequest("digimon")))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("unsupported game: digimon");
  }

  @Test
  void gameMetadataCollectionsShouldBeImmutable() {
    assertThatThrownBy(() -> RegisteredGames.all().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> RegisteredGames.MAGIC_THE_GATHERING.finishes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
