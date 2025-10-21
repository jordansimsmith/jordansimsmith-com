package com.jordansimsmith.footballcalendar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class FakeTeamsFactory implements TeamsFactory {
  private final List<NorthernRegionalFootballTeam> nrfTeams = new ArrayList<>();
  private final List<FootballFixTeam> footballFixTeams = new ArrayList<>();

  @Override
  public List<NorthernRegionalFootballTeam> findNorthernRegionalFootballTeams() {
    return new ArrayList<>(nrfTeams);
  }

  @Override
  public List<FootballFixTeam> findFootballFixTeams() {
    return new ArrayList<>(footballFixTeams);
  }

  @Override
  public Set<String> findTeamIds() {
    var teamIds = new HashSet<String>();
    teamIds.addAll(
        nrfTeams.stream().map(NorthernRegionalFootballTeam::id).collect(Collectors.toSet()));
    teamIds.addAll(footballFixTeams.stream().map(FootballFixTeam::id).collect(Collectors.toSet()));
    return teamIds;
  }

  public void addTeam(NorthernRegionalFootballTeam team) {
    nrfTeams.add(team);
  }

  public void addTeam(FootballFixTeam team) {
    footballFixTeams.add(team);
  }

  public void reset() {
    nrfTeams.clear();
    footballFixTeams.clear();
  }
}
