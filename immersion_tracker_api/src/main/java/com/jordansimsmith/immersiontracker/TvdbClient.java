package com.jordansimsmith.immersiontracker;

import java.time.Duration;

public interface TvdbClient {
  record Show(int id, String name, String image) {}

  record Movie(int id, String name, String image, Duration duration) {}

  Show getShow(int id);

  Movie getMovie(int id);
}
