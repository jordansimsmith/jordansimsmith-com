package com.jordansimsmith.ankibackup;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class AnkiBackupItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String BACKUP_PREFIX = "BACKUP" + DELIMITER;

  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String BACKUP_ID = "backup_id";
  public static final String STATUS = "status";
  public static final String PROFILE_ID = "profile_id";
  public static final String S3_BUCKET = "s3_bucket";
  public static final String S3_KEY = "s3_key";
  public static final String UPLOAD_ID = "upload_id";
  public static final String PART_SIZE_BYTES = "part_size_bytes";
  public static final String SIZE_BYTES = "size_bytes";
  public static final String SHA256 = "sha256";
  public static final String CREATED_AT = "created_at";
  public static final String COMPLETED_AT = "completed_at";
  public static final String EXPIRES_AT = "expires_at";
  public static final String TTL = "ttl";

  public static final String STATUS_PENDING = "PENDING";
  public static final String STATUS_COMPLETED = "COMPLETED";

  private String pk;
  private String sk;
  private String backupId;
  private String status;
  private String profileId;
  private String s3Bucket;
  private String s3Key;
  private String uploadId;
  private Long partSizeBytes;
  private Long sizeBytes;
  private String sha256;
  private Instant createdAt;
  private Instant completedAt;
  private String expiresAt;
  private Long ttl;

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

  @DynamoDbAttribute(BACKUP_ID)
  public String getBackupId() {
    return backupId;
  }

  public void setBackupId(String backupId) {
    this.backupId = backupId;
  }

  @DynamoDbAttribute(STATUS)
  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  @DynamoDbAttribute(PROFILE_ID)
  public String getProfileId() {
    return profileId;
  }

  public void setProfileId(String profileId) {
    this.profileId = profileId;
  }

  @DynamoDbAttribute(S3_BUCKET)
  public String getS3Bucket() {
    return s3Bucket;
  }

  public void setS3Bucket(String s3Bucket) {
    this.s3Bucket = s3Bucket;
  }

  @DynamoDbAttribute(S3_KEY)
  public String getS3Key() {
    return s3Key;
  }

  public void setS3Key(String s3Key) {
    this.s3Key = s3Key;
  }

  @DynamoDbAttribute(UPLOAD_ID)
  public String getUploadId() {
    return uploadId;
  }

  public void setUploadId(String uploadId) {
    this.uploadId = uploadId;
  }

  @DynamoDbAttribute(PART_SIZE_BYTES)
  public Long getPartSizeBytes() {
    return partSizeBytes;
  }

  public void setPartSizeBytes(Long partSizeBytes) {
    this.partSizeBytes = partSizeBytes;
  }

  @DynamoDbAttribute(SIZE_BYTES)
  public Long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(Long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  @DynamoDbAttribute(SHA256)
  public String getSha256() {
    return sha256;
  }

  public void setSha256(String sha256) {
    this.sha256 = sha256;
  }

  @DynamoDbAttribute(CREATED_AT)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  @DynamoDbAttribute(COMPLETED_AT)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }

  @DynamoDbAttribute(EXPIRES_AT)
  public String getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(String expiresAt) {
    this.expiresAt = expiresAt;
  }

  @DynamoDbAttribute(TTL)
  public Long getTtl() {
    return ttl;
  }

  public void setTtl(Long ttl) {
    this.ttl = ttl;
  }

  public static String formatPk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatSk(String backupId) {
    return BACKUP_PREFIX + backupId;
  }
}
