package com.jordansimsmith.tcginventory.imports.server;

public class AppraisalResolverFactory {
  private final AppraisalResolver magicTheGatheringResolver;

  public AppraisalResolverFactory(AppraisalResolver magicTheGatheringResolver) {
    this.magicTheGatheringResolver = magicTheGatheringResolver;
  }

  public AppraisalResolver forGame(String gameId) {
    return switch (gameId) {
      case "mtg" -> magicTheGatheringResolver;
      default ->
          throw new IllegalArgumentException(
              "no appraisal resolver configured for game: " + gameId);
    };
  }
}
