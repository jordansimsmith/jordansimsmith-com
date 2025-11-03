package com.jordansimsmith.footballcalendar;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class TeamsFactoryImpl implements TeamsFactory {
  private static final NorthernRegionalFootballTeam NRF_FLAMINGOS_LEAGUE =
      new NorthernRegionalFootballTeam("Flamingos", "flamingo", "44838", "2716594877", "2025");

  private static final NorthernRegionalFootballTeam NRF_FLAMINGOS_CUP =
      new NorthernRegionalFootballTeam("Flamingos", "flamingo", "44838", "2714644497", "2025");

  private static final FootballFixTeam FOOTBALL_FIX_FLAMINGOS_SEVENS =
      new FootballFixTeam(
          "Flamingos Sevens",
          "flamingoes",
          "13",
          "131",
          "89",
          "6030",
          "3/25 Normanby Road, Mount Eden, Auckland 1024");

  private static final SubfootballTeam SUBFOOTBALL_MAN_I_LOVE_FOOTBALL =
      new SubfootballTeam("Man I Love Football", "4326", "Park Road, Parnell, Auckland 1010");

  @Override
  public List<NorthernRegionalFootballTeam> findNorthernRegionalFootballTeams() {
    return List.of(NRF_FLAMINGOS_LEAGUE, NRF_FLAMINGOS_CUP);
  }

  @Override
  public List<FootballFixTeam> findFootballFixTeams() {
    return List.of(FOOTBALL_FIX_FLAMINGOS_SEVENS);
  }

  @Override
  public List<SubfootballTeam> findSubfootballTeams() {
    return List.of(SUBFOOTBALL_MAN_I_LOVE_FOOTBALL);
  }

  @Override
  public Set<String> findTeamIds() {
    var teamIds = new HashSet<String>();
    teamIds.addAll(
        findNorthernRegionalFootballTeams().stream()
            .map(NorthernRegionalFootballTeam::id)
            .collect(Collectors.toSet()));
    teamIds.addAll(
        findFootballFixTeams().stream().map(FootballFixTeam::id).collect(Collectors.toSet()));
    teamIds.addAll(
        findSubfootballTeams().stream().map(SubfootballTeam::id).collect(Collectors.toSet()));
    return teamIds;
  }
}
