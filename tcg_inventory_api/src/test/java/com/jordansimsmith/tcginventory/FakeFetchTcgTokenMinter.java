package com.jordansimsmith.tcginventory;

public class FakeFetchTcgTokenMinter implements FetchTcgTokenMinter {
  public static final String FAKE_BEARER_TOKEN = "fake-bearer-token";

  @Override
  public String mint(String user) {
    return FAKE_BEARER_TOKEN;
  }
}
