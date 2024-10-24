package com.jordansimsmith.immersiontracker;

public interface TvdbClient {
  record Show(int id, String name, String image) {}

  Show getShow(int id);
}
