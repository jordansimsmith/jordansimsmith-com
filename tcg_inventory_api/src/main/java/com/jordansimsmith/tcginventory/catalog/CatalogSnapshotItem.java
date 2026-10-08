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
  public static final String EXTERNAL_SOURCE = "external_source";
  public static final String CATALOG_SOURCE = "catalog_source";
  public static final String CATEGORY_ID = "category_id";
  public static final String SNAPSHOT_ID = "snapshot_id";
  public static final String SOURCE_MARKER = "source_marker";
  public static final String SOURCE_UPDATED_AT = "source_updated_at";
  public static final String CREATED_AT = "created_at";
  public static final String S3_KEY = "s3_key";
  public static final String SCHEMA_VERSION = "schema_version";
  public static final String CHECKSUM_SHA256 = "checksum_sha256";
  public static final String GROUP_COUNT = "group_count";
  public static final String PRODUCT_COUNT = "product_count";
  public static final String ARTIFACT_SIZE_BYTES = "artifact_size_bytes";
  public static final String SNAPSHOT_PREFIX = "SNAPSHOT#";
  public static final String CATALOG_PREFIX = "CATALOG#";

  private String pk;
  private String sk;
  private String game;
  private String externalSource;
  private String catalogSource;
  private Integer categoryId;
  private String snapshotId;
  private String sourceMarker;
  private Instant sourceUpdatedAt;
  private Instant createdAt;
  private String s3Key;
  private Integer schemaVersion;
  private String checksumSha256;
  private Integer groupCount;
  private Integer productCount;
  private Long artifactSizeBytes;

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

  @DynamoDbAttribute(EXTERNAL_SOURCE)
  public String getExternalSource() {
    return externalSource;
  }

  public void setExternalSource(String externalSource) {
    this.externalSource = externalSource;
  }

  @DynamoDbAttribute(CATALOG_SOURCE)
  public String getCatalogSource() {
    return catalogSource;
  }

  public void setCatalogSource(String catalogSource) {
    this.catalogSource = catalogSource;
  }

  @DynamoDbAttribute(CATEGORY_ID)
  public Integer getCategoryId() {
    return categoryId;
  }

  public void setCategoryId(Integer categoryId) {
    this.categoryId = categoryId;
  }

  @DynamoDbAttribute(SNAPSHOT_ID)
  public String getSnapshotId() {
    return snapshotId;
  }

  public void setSnapshotId(String snapshotId) {
    this.snapshotId = snapshotId;
  }

  @DynamoDbAttribute(SOURCE_MARKER)
  public String getSourceMarker() {
    return sourceMarker;
  }

  public void setSourceMarker(String sourceMarker) {
    this.sourceMarker = sourceMarker;
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

  @DynamoDbAttribute(SCHEMA_VERSION)
  public Integer getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(Integer schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  @DynamoDbAttribute(CHECKSUM_SHA256)
  public String getChecksumSha256() {
    return checksumSha256;
  }

  public void setChecksumSha256(String checksumSha256) {
    this.checksumSha256 = checksumSha256;
  }

  @DynamoDbAttribute(GROUP_COUNT)
  public Integer getGroupCount() {
    return groupCount;
  }

  public void setGroupCount(Integer groupCount) {
    this.groupCount = groupCount;
  }

  @DynamoDbAttribute(PRODUCT_COUNT)
  public Integer getProductCount() {
    return productCount;
  }

  public void setProductCount(Integer productCount) {
    this.productCount = productCount;
  }

  @DynamoDbAttribute(ARTIFACT_SIZE_BYTES)
  public Long getArtifactSizeBytes() {
    return artifactSizeBytes;
  }

  public void setArtifactSizeBytes(Long artifactSizeBytes) {
    this.artifactSizeBytes = artifactSizeBytes;
  }

  public static String formatPk(String game) {
    return CATALOG_PREFIX + game;
  }

  public static String formatSk(String snapshotId) {
    return SNAPSHOT_PREFIX + snapshotId;
  }

  public static CatalogSnapshotItem create(
      String game,
      String externalSource,
      String catalogSource,
      int categoryId,
      String snapshotId,
      String sourceMarker,
      Instant sourceUpdatedAt,
      Instant createdAt,
      String s3Key,
      int schemaVersion,
      String checksumSha256,
      int groupCount,
      int productCount,
      long artifactSizeBytes) {
    var item = new CatalogSnapshotItem();
    item.setPk(formatPk(game));
    item.setSk(formatSk(snapshotId));
    item.setGame(game);
    item.setExternalSource(externalSource);
    item.setCatalogSource(catalogSource);
    item.setCategoryId(categoryId);
    item.setSnapshotId(snapshotId);
    item.setSourceMarker(sourceMarker);
    item.setSourceUpdatedAt(sourceUpdatedAt);
    item.setCreatedAt(createdAt);
    item.setS3Key(s3Key);
    item.setSchemaVersion(schemaVersion);
    item.setChecksumSha256(checksumSha256);
    item.setGroupCount(groupCount);
    item.setProductCount(productCount);
    item.setArtifactSizeBytes(artifactSizeBytes);
    return item;
  }
}
