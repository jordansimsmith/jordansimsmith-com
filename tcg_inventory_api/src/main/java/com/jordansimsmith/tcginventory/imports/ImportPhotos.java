package com.jordansimsmith.tcginventory.imports;

import java.math.BigDecimal;
import javax.annotation.Nullable;

public class ImportPhotos {
  public static final int MAX_PHOTOS = 5;
  private static final BigDecimal IMPORT_GATE = new BigDecimal("20");

  public static boolean needsPhotos(
      @Nullable String decision, @Nullable String suggestedPrice, int photoCount) {
    return "keep".equals(decision)
        && suggestedPrice != null
        && photoCount == 0
        && new BigDecimal(suggestedPrice).compareTo(IMPORT_GATE) >= 0;
  }
}
