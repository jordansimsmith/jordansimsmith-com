package com.jordansimsmith.tcginventory;

import java.math.BigDecimal;
import java.time.Duration;
import javax.annotation.Nullable;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

public class Photos {
  public static final String BUCKET = "api.tcg-inventory.jordansimsmith.com";
  public static final int MAX_PHOTOS = 5;
  public static final int MAX_UPLOAD_BYTES = 4 * 1024 * 1024;
  public static final BigDecimal IMPORT_GATE = new BigDecimal("20");
  public static final BigDecimal PUBLISH_WARNING = new BigDecimal("50");
  public static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

  public static String key(String user, String photoId) {
    return "users/" + user + "/photos/" + photoId + ".jpg";
  }

  public static boolean needsPhotos(
      @Nullable String decision, @Nullable String suggestedPrice, int photoCount) {
    return "keep".equals(decision)
        && suggestedPrice != null
        && photoCount == 0
        && new BigDecimal(suggestedPrice).compareTo(IMPORT_GATE) >= 0;
  }

  public static boolean needsPublishWarning(BigDecimal price, int photoCount) {
    return price.compareTo(PUBLISH_WARNING) >= 0 && photoCount == 0;
  }

  public static String presignedGetUrl(S3Presigner s3Presigner, String user, String photoId) {
    return s3Presigner
        .presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(PRESIGN_TTL)
                .getObjectRequest(
                    GetObjectRequest.builder().bucket(BUCKET).key(key(user, photoId)).build())
                .build())
        .url()
        .toString();
  }
}
