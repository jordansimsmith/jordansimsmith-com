package com.jordansimsmith.tcginventory;

import com.jordansimsmith.tcginventory.games.client.Game;

public class SkuIds {
  public static String format(
      Game game, CardIdentity identity, String finish, Condition condition) {
    if (identity == null) {
      throw new IllegalArgumentException("identity must not be null");
    }
    if (!game.id().equals(identity.game())) {
      throw new IllegalArgumentException("game does not match card identity: " + identity.game());
    }
    if (finish == null
        || game.finishes().stream().noneMatch(candidate -> candidate.id().equals(finish))) {
      throw new IllegalArgumentException(
          "unsupported finish for game " + game.id() + ": " + finish);
    }
    if (condition == null) {
      throw new IllegalArgumentException("condition must not be null");
    }

    return game.id()
        + "#"
        + game.externalSource()
        + "#"
        + identity.externalId()
        + "#"
        + finish
        + "#"
        + condition.name();
  }
}
