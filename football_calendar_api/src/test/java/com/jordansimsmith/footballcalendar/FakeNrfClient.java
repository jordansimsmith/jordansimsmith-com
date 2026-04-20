package com.jordansimsmith.footballcalendar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeNrfClient implements NrfClient {
  private Map<Integer, List<NrfFixture>> fixturesByCompId = new HashMap<>();

  @Override
  public List<NrfFixture> findFixtures(
      List<Integer> compIds,
      List<Integer> orgIds,
      List<Integer> gradeIds,
      Instant from,
      Instant to) {
    return compIds.stream()
        .flatMap(compId -> fixturesByCompId.getOrDefault(compId, List.of()).stream())
        .toList();
  }

  public void addFixture(int compId, NrfFixture fixture) {
    fixturesByCompId.computeIfAbsent(compId, k -> new ArrayList<>()).add(fixture);
  }

  public void reset() {
    fixturesByCompId.clear();
  }
}
