package com.jordansimsmith.tcginventory.fetchtcg;

public class FetchTcgAuthException extends RuntimeException {
  public static final String USER_MESSAGE =
      "FetchTCG authentication failed. Replace the refresh token in settings.";

  private final int statusCode;

  public FetchTcgAuthException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public int getStatusCode() {
    return statusCode;
  }
}
