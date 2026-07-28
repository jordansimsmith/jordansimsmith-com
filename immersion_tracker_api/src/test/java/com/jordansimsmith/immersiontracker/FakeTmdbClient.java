package com.jordansimsmith.immersiontracker;

import java.util.HashMap;
import java.util.Map;

public class FakeTmdbClient implements TmdbClient {
  private final Map<Integer, Movie> movies = new HashMap<>();

  @Override
  public Movie getMovie(int id) {
    return movies.get(id);
  }

  public void addMovie(Movie movie) {
    movies.put(movie.id(), movie);
  }

  public void reset() {
    movies.clear();
  }
}
