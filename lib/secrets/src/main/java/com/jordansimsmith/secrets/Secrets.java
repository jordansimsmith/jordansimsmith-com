package com.jordansimsmith.secrets;

public interface Secrets {
  String get(String name);

  void put(String name, String value);
}
