package com.jordansimsmith.tcginventory;

public class SkuIds {
  public static String format(CardIdentity identity, String finish, Condition condition) {
    if (identity == null) {
      throw new IllegalArgumentException("identity must not be null");
    }
    var game = Games.get(identity.game());
    if (finish == null || !game.finishes().contains(finish)) {
      throw new IllegalArgumentException(
          "unsupported finish for game " + game.id() + ": " + finish);
    }
    if (condition == null) {
      throw new IllegalArgumentException("condition must not be null");
    }

    return game.id()
        + "#"
        + identity.externalSource()
        + "#"
        + identity.externalId()
        + "#"
        + finish
        + "#"
        + condition.name();
  }
}
