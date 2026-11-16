package com.jordansimsmith.tcginventory.imports.server;

public class AppraisalResolverFactory {
  private final AppraisalResolver magicTheGatheringResolver;
  private final AppraisalResolver pokemonResolver;

  public AppraisalResolverFactory(
      AppraisalResolver magicTheGatheringResolver, AppraisalResolver pokemonResolver) {
    this.magicTheGatheringResolver = magicTheGatheringResolver;
    this.pokemonResolver = pokemonResolver;
  }

  public AppraisalResolver forGame(String gameId) {
    return switch (gameId) {
      case "mtg" -> magicTheGatheringResolver;
      case "pokemon" -> pokemonResolver;
      default ->
          throw new IllegalArgumentException(
              "no appraisal resolver configured for game: " + gameId);
    };
  }
}
