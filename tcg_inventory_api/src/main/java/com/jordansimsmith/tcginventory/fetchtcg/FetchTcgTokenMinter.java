package com.jordansimsmith.tcginventory.fetchtcg;

public interface FetchTcgTokenMinter {
  String SECRET_NAME = "tcg_inventory";

  String mint(String user);
}
