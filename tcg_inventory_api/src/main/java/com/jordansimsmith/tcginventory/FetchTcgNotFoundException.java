package com.jordansimsmith.tcginventory;

public class FetchTcgNotFoundException extends RuntimeException {
  public FetchTcgNotFoundException(String message) {
    super(message);
  }
}
