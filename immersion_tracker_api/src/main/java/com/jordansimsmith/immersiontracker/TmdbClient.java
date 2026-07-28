package com.jordansimsmith.immersiontracker;

import java.time.Duration;

public interface TmdbClient {
  record Movie(int id, String name, String image, Duration duration) {}

  Movie getMovie(int id);
}
