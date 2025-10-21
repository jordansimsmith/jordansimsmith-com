package com.jordansimsmith.footballcalendar;

import java.util.List;
import java.util.Set;

public interface TeamsFactory {
  record NorthernRegionalFootballTeam(
      String id, String nameMatcher, String clubId, String competitionId, String seasonId) {}

  record FootballFixTeam(
      String id,
      String nameMatcher,
      String venueId,
      String leagueId,
      String seasonId,
      String divisionId) {}

  List<NorthernRegionalFootballTeam> findNorthernRegionalFootballTeams();

  List<FootballFixTeam> findFootballFixTeams();

  Set<String> findTeamIds();
}
