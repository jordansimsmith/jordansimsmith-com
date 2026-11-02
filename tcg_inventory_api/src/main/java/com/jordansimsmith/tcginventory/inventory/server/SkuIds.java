package com.jordansimsmith.tcginventory.inventory.server;

import com.jordansimsmith.tcginventory.games.client.Game;
import com.jordansimsmith.tcginventory.inventory.client.Condition;

public class SkuIds {
  public static String format(Game game, String externalId, String finish, Condition condition) {
    if (!game.finishes().stream().anyMatch(candidate -> candidate.id().equals(finish))) {
      throw new IllegalArgumentException("unsupported finish for game " + game.id());
    }
    return game.id()
        + "#"
        + game.externalSource()
        + "#"
        + externalId
        + "#"
        + finish
        + "#"
        + condition;
  }
}
