package com.jordansimsmith.tcginventory.fetchtcg.server;

public class FetchTcgAuthenticationException extends RuntimeException {
  private final int statusCode;

  public FetchTcgAuthenticationException(int statusCode) {
    super("FetchTCG authentication failed with status " + statusCode);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
