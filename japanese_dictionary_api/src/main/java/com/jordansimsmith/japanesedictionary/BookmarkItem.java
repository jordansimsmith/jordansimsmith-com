package com.jordansimsmith.japanesedictionary;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import java.time.Instant;
import java.util.Objects;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class BookmarkItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String BOOKMARK_PREFIX = "BOOKMARK" + DELIMITER;

  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String USER = "user";
  public static final String SEQUENCE = "sequence";
  public static final String CREATED_AT = "created_at";

  private String pk;
  private String sk;
  private String user;
  private Long sequence;
  private Instant createdAt;

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

  @DynamoDbAttribute(USER)
  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
  }

  @DynamoDbAttribute(SEQUENCE)
  public Long getSequence() {
    return sequence;
  }

  public void setSequence(Long sequence) {
    this.sequence = sequence;
  }

  @DynamoDbAttribute(CREATED_AT)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    BookmarkItem that = (BookmarkItem) o;
    return Objects.equals(pk, that.pk)
        && Objects.equals(sk, that.sk)
        && Objects.equals(user, that.user)
        && Objects.equals(sequence, that.sequence)
        && Objects.equals(createdAt, that.createdAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(pk, sk, user, sequence, createdAt);
  }

  @Override
  public String toString() {
    return "BookmarkItem{"
        + "pk='"
        + pk
        + '\''
        + ", sk='"
        + sk
        + '\''
        + ", user='"
        + user
        + '\''
        + ", sequence="
        + sequence
        + ", createdAt="
        + createdAt
        + '}';
  }

  public static String formatPk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatSk(long sequence) {
    return BOOKMARK_PREFIX + sequence;
  }

  public static BookmarkItem create(String user, long sequence, Instant createdAt) {
    var item = new BookmarkItem();
    item.setPk(formatPk(user));
    item.setSk(formatSk(sequence));
    item.setUser(user);
    item.setSequence(sequence);
    item.setCreatedAt(createdAt);
    return item;
  }
}
