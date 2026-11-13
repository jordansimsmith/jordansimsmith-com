package com.jordansimsmith.tcginventory.scans.server;

import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class ScanUploadVerifier {
  private final S3Client s3Client;
  private final String bucket;

  public ScanUploadVerifier(S3Client s3Client, String bucket) {
    this.s3Client = s3Client;
    this.bucket = bucket;
  }

  public boolean isUploaded(ScanRowItem row) {
    try {
      var head =
          s3Client.headObject(
              HeadObjectRequest.builder().bucket(bucket).key(row.getS3Key()).build());
      return Objects.equals(head.contentLength(), row.getSizeBytes())
          && ScanImages.CONTENT_TYPE.equals(head.contentType());
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return false;
      }
      throw e;
    }
  }

  public boolean areUploaded(List<ScanRowItem> rows) {
    return rows.stream().allMatch(this::isUploaded);
  }
}
