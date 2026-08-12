package com.jordansimsmith.tcginventory;

public class FetchTcgAuthException extends RuntimeException {
  private final int statusCode;

  public FetchTcgAuthException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public int getStatusCode() {
    return statusCode;
  }
}
