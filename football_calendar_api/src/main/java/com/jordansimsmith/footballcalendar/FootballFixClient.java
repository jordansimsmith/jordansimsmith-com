package com.jordansimsmith.footballcalendar;

import java.time.Instant;
import java.util.List;

public interface FootballFixClient {
  record FootballFixture(
      String id, String homeTeamName, String awayTeamName, Instant timestamp, String venue) {}

  List<FootballFixture> findFixtures(
      String venueId, String leagueId, String seasonId, String divisionId);
}
