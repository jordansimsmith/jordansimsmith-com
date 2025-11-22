package com.jordansimsmith.immersiontracker;

import java.util.HashMap;
import java.util.Map;

public class FakeTvdbClient implements TvdbClient {
  private final Map<Integer, Show> shows = new HashMap<>();
  private final Map<Integer, Movie> movies = new HashMap<>();

  @Override
  public Show getShow(int id) {
    return shows.get(id);
  }

  @Override
  public Movie getMovie(int id) {
    return movies.get(id);
  }

  public void addShow(Show show) {
    shows.put(show.id(), show);
  }

  public void addMovie(Movie movie) {
    movies.put(movie.id(), movie);
  }

  public void reset() {
    shows.clear();
    movies.clear();
  }
}
