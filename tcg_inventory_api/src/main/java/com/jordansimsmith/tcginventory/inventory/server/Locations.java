package com.jordansimsmith.tcginventory.inventory.server;

public class Locations {
  public static String format(int sequenceNumber) {
    var blockNumber = sequenceNumber / 100;
    var offset = sequenceNumber % 100;
    return format(blockNumber, offset);
  }

  public static String format(int blockNumber, int offset) {
    var letter = (char) ('A' + blockNumber / 100);
    return "" + letter + blockNumber % 100 + "-" + offset;
  }
}
