package com.jordansimsmith.tcginventory.games.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.games.client.FindGamesRequest;
import com.jordansimsmith.tcginventory.games.client.GetGameRequest;
import org.junit.jupiter.api.Test;

public class RegisteredGamesTest {
  @Test
  void findGamesShouldReturnOrderedRegisteredMetadata() throws Exception {
    var response =
        new FindGamesService().findGames(new RequestContext("user"), new FindGamesRequest());

    assertThat(response.games())
        .containsExactly(RegisteredGames.MAGIC_THE_GATHERING, RegisteredGames.POKEMON_ENGLISH);
    assertThat(response.games().get(0).externalSource()).isEqualTo("scryfall");
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
