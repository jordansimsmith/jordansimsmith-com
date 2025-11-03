package com.jordansimsmith.footballcalendar;

import java.time.Instant;
import java.util.List;

public interface SubfootballClient {
  record SubfootballFixture(
      String id, String homeTeamName, String awayTeamName, Instant timestamp, String venue) {}

  List<SubfootballFixture> getFixtures(String teamId);
}
