package com.jordansimsmith.footballcalendar;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class TeamsFactoryImpl implements TeamsFactory {
  private static final NorthernRegionalFootballTeam NRF_FLAMINGOS_LEAGUE =
      new NorthernRegionalFootballTeam("Flamingos", "flamingo", "44838", "2716594877");

  private static final NorthernRegionalFootballTeam NRF_FLAMINGOS_CUP =
      new NorthernRegionalFootballTeam("Flamingos", "flamingo", "44838", "2714644497");

  @Override
  public List<NorthernRegionalFootballTeam> findNorthernRegionalFootballTeams() {
    return List.of(NRF_FLAMINGOS_LEAGUE, NRF_FLAMINGOS_CUP);
  }

  @Override
  public Set<String> findTeamIds() {
    return findNorthernRegionalFootballTeams().stream()
        .map(NorthernRegionalFootballTeam::id)
        .collect(Collectors.toSet());
  }
}
