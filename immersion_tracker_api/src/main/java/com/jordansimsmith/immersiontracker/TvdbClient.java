package com.jordansimsmith.immersiontracker;

import java.time.Duration;

public interface TvdbClient {
  record Show(int id, String name, String image, Duration averageRuntime) {}

  Show getShow(int id);
}
