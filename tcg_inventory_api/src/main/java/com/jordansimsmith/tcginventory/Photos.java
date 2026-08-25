package com.jordansimsmith.tcginventory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import javax.annotation.Nullable;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
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
      @Nullable String decision,
      @Nullable String suggestedPrice,
      @Nullable List<TcgInventoryItem.Photo> photos) {
    return "keep".equals(decision)
        && suggestedPrice != null
        && (photos == null || photos.isEmpty())
        && new BigDecimal(suggestedPrice).compareTo(IMPORT_GATE) >= 0;
  }

  public static boolean needsPublishWarning(
      BigDecimal price, @Nullable List<TcgInventoryItem.Photo> photos) {
    return price.compareTo(PUBLISH_WARNING) >= 0 && (photos == null || photos.isEmpty());
  }

  public static AttributeValue toAttributeValue(List<TcgInventoryItem.Photo> photos) {
    return AttributeValue.builder()
        .l(
            photos.stream()
                .map(
                    photo -> {
                      var map = new HashMap<String, AttributeValue>();
                      map.put(
                          TcgInventoryItem.PHOTO_ID,
                          AttributeValue.builder().s(photo.getPhotoId()).build());
                      if (photo.getFetchtcgUrl() != null) {
                        map.put(
                            TcgInventoryItem.FETCHTCG_URL,
                            AttributeValue.builder().s(photo.getFetchtcgUrl()).build());
                      }
                      return AttributeValue.builder().m(map).build();
                    })
                .toList())
        .build();
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
