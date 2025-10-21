package com.jordansimsmith.footballcalendar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeFootballFixClient implements FootballFixClient {
  private final Map<String, List<FootballFixture>> fixturesByDivision = new HashMap<>();

  @Override
  public List<FootballFixture> getFixtures(
      String venueId, String leagueId, String seasonId, String divisionId) {
    return fixturesByDivision.getOrDefault(divisionId, List.of());
  }

  public void addFixture(String divisionId, FootballFixture fixture) {
    fixturesByDivision.computeIfAbsent(divisionId, k -> new ArrayList<>()).add(fixture);
  }

  public void reset() {
    fixturesByDivision.clear();
  }
}
