package com.jordansimsmith.tcginventory;

public class InventoryLocation {
  public static String formatBlock(int blockNum) {
    char letter = (char) ('A' + blockNum / 100);
    int number = blockNum % 100;
    return "" + letter + number;
  }

  public static String formatLocation(int sequenceNumber) {
    int blockNum = sequenceNumber / 100;
    int offset = sequenceNumber % 100;
    return formatBlock(blockNum) + "-" + offset;
  }
}
