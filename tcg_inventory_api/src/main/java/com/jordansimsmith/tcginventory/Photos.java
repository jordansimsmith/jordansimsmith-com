package com.jordansimsmith.tcginventory;

public class Photos {
  public static final String BUCKET = "api.tcg-inventory.jordansimsmith.com";
  public static final int MAX_PHOTOS = 5;
  public static final int MAX_UPLOAD_BYTES = 4 * 1024 * 1024;

  public static String key(String user, String photoId) {
    return "users/" + user + "/photos/" + photoId + ".jpg";
  }
}
