package com.jordansimsmith.eventcalendar;

import java.util.ArrayList;
import java.util.List;

public class FakeLeinsterRugbyClient implements LeinsterRugbyClient {
  private final List<LeinsterFixture> fixtures = new ArrayList<>();

  @Override
  public List<LeinsterFixture> findFixtures() {
    return fixtures;
  }

  public void addFixture(LeinsterFixture fixture) {
    fixtures.add(fixture);
  }

  public void reset() {
    fixtures.clear();
  }
}
