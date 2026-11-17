package com.jordansimsmith.tcginventory.imports.server;

public class AppraisalResolverFactory {
  private final AppraisalResolver magicTheGatheringResolver;
  private final AppraisalResolver pokemonResolver;
  private final AppraisalResolver pokemonJpResolver;

  public AppraisalResolverFactory(
      AppraisalResolver magicTheGatheringResolver,
      AppraisalResolver pokemonResolver,
      AppraisalResolver pokemonJpResolver) {
    this.magicTheGatheringResolver = magicTheGatheringResolver;
    this.pokemonResolver = pokemonResolver;
    this.pokemonJpResolver = pokemonJpResolver;
  }

  public AppraisalResolver forGame(String gameId) {
    return switch (gameId) {
      case "mtg" -> magicTheGatheringResolver;
      case "pokemon" -> pokemonResolver;
      case "pokemon_jp" -> pokemonJpResolver;
      default ->
          throw new IllegalArgumentException(
              "no appraisal resolver configured for game: " + gameId);
    };
  }
}
