package com.jordansimsmith.eventcalendar;

import java.time.Instant;
import java.util.List;

public interface LeinsterRugbyClient {
  String FIXTURES_URL =
      "https://stats-api.leinster.soticclient.net/custom/fixtureList/"
          + "9941733f-560d-4cd3-89d1-78af7cd3b995/76c82394-adfd-4cdb-8c02-a65a03ec1f88";
  String PUBLIC_FIXTURES_URL = "https://www.leinsterrugby.ie/teams/mens-senior/mens-matches/";

  List<LeinsterFixture> findFixtures();

  record LeinsterFixture(
      String fixtureId, String title, Instant startTime, String competition, String location) {}
}
