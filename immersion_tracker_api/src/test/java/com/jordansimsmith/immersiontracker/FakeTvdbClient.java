package com.jordansimsmith.immersiontracker;

import java.util.HashMap;
import java.util.Map;

public class FakeTvdbClient implements TvdbClient {
  private final Map<Integer, Show> shows = new HashMap<>();

  @Override
  public Show getShow(int id) {
    return shows.get(id);
  }

  public void addShow(Show show) {
    shows.put(show.id(), show);
  }

  public void reset() {
    shows.clear();
  }
}
