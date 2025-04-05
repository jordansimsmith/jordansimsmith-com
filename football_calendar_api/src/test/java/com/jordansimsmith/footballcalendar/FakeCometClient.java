package com.jordansimsmith.footballcalendar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class FakeCometClient implements CometClient {
  private List<FootballFixture> fixtures = new ArrayList<>();

  @Override
  public List<FootballFixture> getFixtures(
      String seasonId,
      String competitionId,
      List<String> organisationIds,
      Instant from,
      Instant to) {
    return fixtures;
  }

  public void addFixture(FootballFixture fixture) {
    fixtures.add(fixture);
  }

  public void reset() {
    fixtures.clear();
  }
}
