package com.jordansimsmith.tcginventory.catalog;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class CatalogSnapshotItem {
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String GAME = "game";
  public static final String SNAPSHOT_ID = "snapshot_id";
  public static final String SOURCE_UPDATED_AT = "source_updated_at";
  public static final String CREATED_AT = "created_at";
  public static final String S3_KEY = "s3_key";
  public static final String CHECKSUM_SHA256 = "checksum_sha256";
  public static final String SNAPSHOT_PREFIX = "SNAPSHOT#";
  public static final String CATALOG_PREFIX = "CATALOG#";

  private String pk;
  private String sk;
  private String game;
  private String snapshotId;
  private Instant sourceUpdatedAt;
  private Instant createdAt;
  private String s3Key;
  private String checksumSha256;

  @DynamoDbPartitionKey
  @DynamoDbAttribute(PK)
  public String getPk() {
    return pk;
  }

  public void setPk(String pk) {
    this.pk = pk;
  }

  @DynamoDbSortKey
  @DynamoDbAttribute(SK)
  public String getSk() {
    return sk;
  }

  public void setSk(String sk) {
    this.sk = sk;
  }

  @DynamoDbAttribute(GAME)
  public String getGame() {
    return game;
  }

  public void setGame(String game) {
    this.game = game;
  }

  @DynamoDbAttribute(SNAPSHOT_ID)
  public String getSnapshotId() {
    return snapshotId;
  }

  public void setSnapshotId(String snapshotId) {
    this.snapshotId = snapshotId;
  }

  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(SOURCE_UPDATED_AT)
  public Instant getSourceUpdatedAt() {
    return sourceUpdatedAt;
  }

  public void setSourceUpdatedAt(@Nullable Instant sourceUpdatedAt) {
    this.sourceUpdatedAt = sourceUpdatedAt;
  }

  @DynamoDbConvertedBy(EpochSecondConverter.class)
  @DynamoDbAttribute(CREATED_AT)
  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(@Nullable Instant createdAt) {
    this.createdAt = createdAt;
  }

  @DynamoDbAttribute(S3_KEY)
  public String getS3Key() {
    return s3Key;
  }

  public void setS3Key(String s3Key) {
    this.s3Key = s3Key;
  }

  @DynamoDbAttribute(CHECKSUM_SHA256)
  public String getChecksumSha256() {
    return checksumSha256;
  }

  public void setChecksumSha256(String checksumSha256) {
    this.checksumSha256 = checksumSha256;
  }

  public static String formatPk(String game) {
    return CATALOG_PREFIX + game;
  }

  public static String formatSk(String snapshotId) {
    return SNAPSHOT_PREFIX + snapshotId;
  }

  public static CatalogSnapshotItem create(
      String game,
      String snapshotId,
      Instant sourceUpdatedAt,
      Instant createdAt,
      String s3Key,
      String checksumSha256) {
    var item = new CatalogSnapshotItem();
    item.setPk(formatPk(game));
    item.setSk(formatSk(snapshotId));
    item.setGame(game);
    item.setSnapshotId(snapshotId);
    item.setSourceUpdatedAt(sourceUpdatedAt);
    item.setCreatedAt(createdAt);
    item.setS3Key(s3Key);
    item.setChecksumSha256(checksumSha256);
    return item;
  }
}
