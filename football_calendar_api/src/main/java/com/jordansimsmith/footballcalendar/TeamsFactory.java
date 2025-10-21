package com.jordansimsmith.footballcalendar;

import java.util.List;
import java.util.Set;

public interface TeamsFactory {
  record NorthernRegionalFootballTeam(
      String id, String nameMatcher, String clubId, String competitionId, String seasonId) {}

  List<NorthernRegionalFootballTeam> findNorthernRegionalFootballTeams();

  Set<String> findTeamIds();
}
