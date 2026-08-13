package com.jordansimsmith.tcginventory;

public interface FetchTcgTokenMinter {
  String SECRET_NAME = "tcg_inventory";

  String mint(String user);
}
