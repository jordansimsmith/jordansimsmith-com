package com.jordansimsmith.tcginventory.scans;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class ScanRowItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String SCAN_PREFIX = "SCAN" + DELIMITER;
  public static final String ROW_PREFIX = "ROW" + DELIMITER;
  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String SCAN_ID = "scan_id";
  public static final String SCAN_POSITION = "scan_position";
  public static final String FILENAME = "filename";
  public static final String SIZE_BYTES = "size_bytes";
  public static final String S3_KEY = "s3_key";
  public static final String STATUS = "status";
  public static final String NEEDS_REVIEW = "needs_review";
  public static final String SUGGESTIONS = "suggestions";
  public static final String ERROR = "error";

  private String pk;
  private String sk;
  private String scanId;
  private Integer scanPosition;
  private String filename;
  private Long sizeBytes;
  private String s3Key;
  private String status;
  private Boolean needsReview;
  private List<ScanSuggestion> suggestions;
  private String error;

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

  @DynamoDbAttribute(SCAN_ID)
  public String getScanId() {
    return scanId;
  }

  public void setScanId(@Nullable String scanId) {
    this.scanId = scanId;
  }

  @DynamoDbAttribute(SCAN_POSITION)
  public Integer getScanPosition() {
    return scanPosition;
  }

  public void setScanPosition(@Nullable Integer scanPosition) {
    this.scanPosition = scanPosition;
  }

  @DynamoDbAttribute(FILENAME)
  public String getFilename() {
    return filename;
  }

  public void setFilename(@Nullable String filename) {
    this.filename = filename;
  }

  @DynamoDbAttribute(SIZE_BYTES)
  public Long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(@Nullable Long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  @DynamoDbAttribute(S3_KEY)
  public String getS3Key() {
    return s3Key;
  }

  public void setS3Key(@Nullable String s3Key) {
    this.s3Key = s3Key;
  }

  @DynamoDbAttribute(STATUS)
  public String getStatus() {
    return status;
  }

  public void setStatus(@Nullable String status) {
    this.status = status;
  }

  @DynamoDbAttribute(NEEDS_REVIEW)
  public Boolean getNeedsReview() {
    return needsReview;
  }

  public void setNeedsReview(@Nullable Boolean needsReview) {
    this.needsReview = needsReview;
  }

  @DynamoDbAttribute(SUGGESTIONS)
  public List<ScanSuggestion> getSuggestions() {
    return suggestions;
  }

  public void setSuggestions(@Nullable List<ScanSuggestion> suggestions) {
    this.suggestions = suggestions;
  }

  @DynamoDbAttribute(ERROR)
  public String getError() {
    return error;
  }

  public void setError(@Nullable String error) {
    this.error = error;
  }

  public static String formatPk(String user, String scanId) {
    return USER_PREFIX + user + DELIMITER + SCAN_PREFIX + scanId;
  }

  public static String formatSk(int scanPosition) {
    return ROW_PREFIX + String.format(Locale.ROOT, "%06d", scanPosition);
  }

  public static ScanRowItem create(
      String user, String scanId, int scanPosition, String filename, long sizeBytes, String s3Key) {
    var item = new ScanRowItem();
    item.setPk(formatPk(user, scanId));
    item.setSk(formatSk(scanPosition));
    item.setScanId(scanId);
    item.setScanPosition(scanPosition);
    item.setFilename(filename);
    item.setSizeBytes(sizeBytes);
    item.setS3Key(s3Key);
    item.setStatus("pending");
    item.setNeedsReview(false);
    return item;
  }

  @DynamoDbBean
  public static class ScanSuggestion {
    public static final String SCRYFALL_ID = "scryfall_id";
    public static final String NAME = "name";
    public static final String SCORE = "score";

    private String scryfallId;
    private String name;
    private Double score;

    @DynamoDbAttribute(SCRYFALL_ID)
    public String getScryfallId() {
      return scryfallId;
    }

    public void setScryfallId(@Nullable String scryfallId) {
      this.scryfallId = scryfallId;
    }

    @DynamoDbAttribute(NAME)
    public String getName() {
      return name;
    }

    public void setName(@Nullable String name) {
      this.name = name;
    }

    @DynamoDbAttribute(SCORE)
    public Double getScore() {
      return score;
    }

    public void setScore(@Nullable Double score) {
      this.score = score;
    }

    public static ScanSuggestion create(String scryfallId, String name, double score) {
      var suggestion = new ScanSuggestion();
      suggestion.setScryfallId(scryfallId);
      suggestion.setName(name);
      suggestion.setScore(score);
      return suggestion;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ScanSuggestion that = (ScanSuggestion) o;
      return Objects.equals(scryfallId, that.scryfallId)
          && Objects.equals(name, that.name)
          && Objects.equals(score, that.score);
    }

    @Override
    public int hashCode() {
      return Objects.hash(scryfallId, name, score);
    }
  }
}
