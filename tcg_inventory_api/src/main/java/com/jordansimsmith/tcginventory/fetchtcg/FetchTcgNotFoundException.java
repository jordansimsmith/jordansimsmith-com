package com.jordansimsmith.tcginventory.fetchtcg;

public class FetchTcgNotFoundException extends RuntimeException {
  public FetchTcgNotFoundException(String message) {
    super(message);
  }
}
