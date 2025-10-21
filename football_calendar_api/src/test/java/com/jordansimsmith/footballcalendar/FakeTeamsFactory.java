package com.jordansimsmith.footballcalendar;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class FakeTeamsFactory implements TeamsFactory {
  private final List<NorthernRegionalFootballTeam> nrfTeams = new ArrayList<>();

  @Override
  public List<NorthernRegionalFootballTeam> findNorthernRegionalFootballTeams() {
    return new ArrayList<>(nrfTeams);
  }

  @Override
  public Set<String> findTeamIds() {
    return nrfTeams.stream().map(NorthernRegionalFootballTeam::id).collect(Collectors.toSet());
  }

  public void addTeam(NorthernRegionalFootballTeam team) {
    nrfTeams.add(team);
  }

  public void reset() {
    nrfTeams.clear();
  }
}
