package com.jordansimsmith.footballcalendar;

import java.time.Instant;
import java.util.List;

public interface CometClient {
  record FootballFixture(
      String id,
      String homeTeamName,
      String awayTeamName,
      Instant timestamp,
      String venue,
      String address,
      Double latitude,
      Double longitude,
      String status) {}

  List<FootballFixture> findFixtures(
      String seasonId,
      String competitionId,
      List<String> organisationIds,
      Instant from,
      Instant to);
}
