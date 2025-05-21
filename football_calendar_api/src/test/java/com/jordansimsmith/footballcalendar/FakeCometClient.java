package com.jordansimsmith.footballcalendar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeCometClient implements CometClient {
  private Map<String, List<FootballFixture>> fixturesByCompetition = new HashMap<>();

  @Override
  public List<FootballFixture> getFixtures(
      String seasonId,
      String competitionId,
      List<String> organisationIds,
      Instant from,
      Instant to) {
    return fixturesByCompetition.getOrDefault(competitionId, List.of());
  }

  public void addFixture(String competitionId, FootballFixture fixture) {
    fixturesByCompetition.computeIfAbsent(competitionId, k -> new ArrayList<>()).add(fixture);
  }

  public void reset() {
    fixturesByCompetition.clear();
  }
}
