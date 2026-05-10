package com.jordansimsmith.japanesedictionary;

import java.util.Set;

public class PitchPosition {
  private static final Set<Character> SMALL_YA_YU_YO =
      Set.of('ゃ', 'ゅ', 'ょ', 'ゎ', 'ャ', 'ュ', 'ョ', 'ヮ');

  public int moraCount(String reading) {
    if (reading == null) {
      return 0;
    }
    var count = 0;
    for (var i = 0; i < reading.length(); i++) {
      if (!SMALL_YA_YU_YO.contains(reading.charAt(i))) {
        count++;
      }
    }
    return count;
  }

  public boolean validate(int position, int moraCount) {
    return position >= 0 && position <= moraCount;
  }
}
