package com.jordansimsmith.footballcalendar;

import java.time.Instant;
import java.util.List;

public interface NrfClient {
  record NrfFixture(
      String id,
      String homeTeamName,
      String awayTeamName,
      Instant timestamp,
      String venue,
      String address,
      Double latitude,
      Double longitude,
      String status) {}

  List<NrfFixture> findFixtures(
      List<Integer> compIds,
      List<Integer> orgIds,
      List<Integer> gradeIds,
      Instant from,
      Instant to);
}
