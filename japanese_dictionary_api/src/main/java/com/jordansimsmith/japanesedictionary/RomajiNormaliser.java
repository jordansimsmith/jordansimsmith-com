package com.jordansimsmith.japanesedictionary;

import java.util.Locale;

public class RomajiNormaliser {

  public String normalise(String input) {
    if (input == null) {
      return null;
    }

    var s = input.toLowerCase(Locale.ROOT);

    s = s.replace("\u0304", "").replace("\u0302", "");

    s = s.replace("ō", "ou").replace("ô", "ou");
    s = s.replace("ū", "uu").replace("û", "uu");
    s = s.replace("ē", "ee");
    s = s.replace("ā", "aa");
    s = s.replace("ī", "ii");

    s = s.replace("sy", "sh");
    s = s.replace("ty", "ch");
    s = s.replace("zy", "j");
    s = s.replace("si", "shi");
    s = s.replace("ti", "chi");
    s = s.replace("tu", "tsu");
    s = s.replaceAll("(?<![sc])hu", "fu");
    s = s.replace("zi", "ji");
    s = s.replace("di", "ji");
    s = s.replace("du", "zu");

    s = s.replace("'", "");

    return s;
  }
}
