package com.jordansimsmith.tcginventory;

import java.util.Map;

public enum Condition {
  NM("raw-nm", 4),
  LP("raw-lp", 3),
  MP("raw-mp", 2),
  HP("raw-hp", 1),
  DMG("raw-d", 0);

  private static final Map<String, Condition> MANABOX_MAP =
      Map.ofEntries(
          Map.entry("mint", NM),
          Map.entry("near_mint", NM),
          Map.entry("excellent", LP),
          Map.entry("good", MP),
          Map.entry("light_played", HP),
          Map.entry("played", HP),
          Map.entry("poor", DMG));

  private static final Map<String, Condition> FETCHTCG_MAP =
      Map.of("raw-nm", NM, "raw-lp", LP, "raw-mp", MP, "raw-hp", HP, "raw-d", DMG);

  private final String fetchtcgCode;
  private final int quality;

  Condition(String fetchtcgCode, int quality) {
    this.fetchtcgCode = fetchtcgCode;
    this.quality = quality;
  }

  public String toFetchtcg() {
    return fetchtcgCode;
  }

  public int quality() {
    return quality;
  }

  public boolean isSameOrBetterThan(Condition other) {
    return this.quality >= other.quality;
  }

  public static Condition fromManaBox(String value) {
    return MANABOX_MAP.get(value);
  }

  public static Condition fromFetchtcg(String code) {
    return FETCHTCG_MAP.get(code);
  }
}
