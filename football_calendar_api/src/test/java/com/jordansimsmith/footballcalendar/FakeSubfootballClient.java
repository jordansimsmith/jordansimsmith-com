package com.jordansimsmith.footballcalendar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeSubfootballClient implements SubfootballClient {
  private final Map<String, List<SubfootballFixture>> fixturesByTeam = new HashMap<>();

  @Override
  public List<SubfootballFixture> findFixtures(String teamId) {
    return fixturesByTeam.getOrDefault(teamId, List.of());
  }

  public void addFixture(String teamId, SubfootballFixture fixture) {
    fixturesByTeam.computeIfAbsent(teamId, k -> new ArrayList<>()).add(fixture);
  }

  public void reset() {
    fixturesByTeam.clear();
  }
}
