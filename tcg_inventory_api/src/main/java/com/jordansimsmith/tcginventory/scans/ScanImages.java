package com.jordansimsmith.tcginventory.scans;

import java.time.Duration;

public class ScanImages {
  public static final String BUCKET = "api.tcg-inventory.jordansimsmith.com";
  public static final String CONTENT_TYPE = "image/jpeg";
  public static final Duration PRESIGN_TTL = Duration.ofMinutes(15);
}
