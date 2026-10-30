package com.jordansimsmith.tcginventory.photos.server;

import java.time.Duration;
import java.util.List;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

public class S3PhotoStore {
  private static final Duration URL_EXPIRY = Duration.ofMinutes(15);

  private final S3Client s3Client;
  private final S3Presigner s3Presigner;
  private final String bucket;

  public S3PhotoStore(S3Client s3Client, S3Presigner s3Presigner, String bucket) {
    this.s3Client = s3Client;
    this.s3Presigner = s3Presigner;
    this.bucket = bucket;
  }

  public void create(String user, String photoId, byte[] photoBytes) {
    s3Client.putObject(
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(key(user, photoId))
            .contentType("image/jpeg")
            .build(),
        RequestBody.fromBytes(photoBytes));
  }

  public byte[] getBytes(String user, String photoId) {
    ResponseBytes<GetObjectResponse> objectBytes =
        s3Client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key(key(user, photoId)).build());
    return objectBytes.asByteArray();
  }

  public void delete(String user, String photoId) {
    s3Client.deleteObject(
        DeleteObjectRequest.builder().bucket(bucket).key(key(user, photoId)).build());
  }

  public List<String> findUrls(String user, List<String> photoIds) {
    return photoIds.stream()
        .map(
            photoId ->
                s3Presigner
                    .presignGetObject(
                        GetObjectPresignRequest.builder()
                            .signatureDuration(URL_EXPIRY)
                            .getObjectRequest(
                                GetObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(key(user, photoId))
                                    .build())
                            .build())
                    .url()
                    .toString())
        .toList();
  }

  private static String key(String user, String photoId) {
    return "users/" + user + "/photos/" + photoId + ".jpg";
  }
}
