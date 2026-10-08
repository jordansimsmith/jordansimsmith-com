package com.jordansimsmith.tcginventory.catalog;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class CatalogSnapshotStore {
  public static final String BUCKET = "api.tcg-inventory.jordansimsmith.com";

  private final S3Client s3Client;
  private final String bucket;
  private final CatalogRepository catalogRepository;
  private final TcgCsvCatalogArtifactCodec artifactCodec;

  public CatalogSnapshotStore(
      S3Client s3Client,
      String bucket,
      CatalogRepository catalogRepository,
      TcgCsvCatalogArtifactCodec artifactCodec) {
    this.s3Client = s3Client;
    this.bucket = bucket;
    this.catalogRepository = catalogRepository;
    this.artifactCodec = artifactCodec;
  }

  public CatalogSnapshotItem createSnapshot(TcgCsvCatalogSnapshot snapshot) throws IOException {
    var existing = catalogRepository.getSnapshot(snapshot.game(), snapshot.snapshotId());
    if (existing != null) {
      return existing;
    }

    var candidateBytes = artifactCodec.encodeGzip(snapshot);
    var s3Key = key(snapshot.game(), snapshot.snapshotId());
    byte[] storedBytes;
    try {
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(s3Key)
              .contentType("application/gzip")
              .ifNoneMatch("*")
              .build(),
          RequestBody.fromBytes(candidateBytes));
      storedBytes = candidateBytes;
    } catch (S3Exception e) {
      if (e.statusCode() != 412) {
        throw e;
      }
      storedBytes = getObject(s3Key);
    }

    var storedSnapshot = artifactCodec.decodeGzip(storedBytes);
    var item =
        CatalogSnapshotItem.create(
            storedSnapshot.game(),
            storedSnapshot.snapshotId(),
            Instant.ofEpochSecond(storedSnapshot.sourceUpdatedAt()),
            Instant.ofEpochSecond(storedSnapshot.createdAt()),
            s3Key,
            checksum(storedBytes));
    return catalogRepository.createSnapshot(item);
  }

  public TcgCsvCatalogSnapshot getSnapshot(CatalogSnapshotItem item) throws IOException {
    return artifactCodec.decodeGzip(getObject(item.getS3Key()));
  }

  public static String key(String game, String snapshotId) {
    return "catalogs/tcgcsv/%s/snapshots/%s.json.gz".formatted(game, snapshotId);
  }

  private byte[] getObject(String s3Key) {
    return s3Client
        .getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(s3Key).build())
        .asByteArray();
  }

  private String checksum(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
