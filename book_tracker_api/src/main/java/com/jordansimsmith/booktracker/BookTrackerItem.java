package com.jordansimsmith.booktracker;

import com.jordansimsmith.dynamodb.EpochSecondConverter;
import com.jordansimsmith.dynamodb.LocalDateConverter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class BookTrackerItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String BOOK_PREFIX = "BOOK" + DELIMITER;
  public static final String FINISHED_PREFIX = "FINISHED" + DELIMITER;

  public static final String TABLE_NAME = "book_tracker";
  public static final String GSI1_NAME = "gsi1";

  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String GSI1PK = "gsi1pk";
  public static final String GSI1SK = "gsi1sk";
  public static final String USER = "user";
  public static final String OPEN_LIBRARY_WORK_ID = "open_library_work_id";
  public static final String TITLE = "title";
  public static final String AUTHORS = "authors";
  public static final String COVER_URL = "cover_url";
  public static final String PAGE_COUNT = "page_count";
  public static final String PUBLICATION_YEAR = "publication_year";
  public static final String FINISHED_DATE = "finished_date";
  public static final String CREATED_AT = "created_at";
  public static final String UPDATED_AT = "updated_at";

  private String pk;
  private String sk;
  private String gsi1pk;
  private String gsi1sk;
  private String user;
  private String openLibraryWorkId;
  private String title;
  private List<String> authors;
  private String coverUrl;
  private Integer pageCount;
  private Integer publicationYear;
  private LocalDate finishedDate;
  private Instant createdAt;
  private Instant updatedAt;

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

  @DynamoDbSecondaryPartitionKey(indexNames = GSI1_NAME)
  @DynamoDbAttribute(GSI1PK)
  public String getGsi1pk() {
    return gsi1pk;
  }

  public void setGsi1pk(String gsi1pk) {
    this.gsi1pk = gsi1pk;
  }

  @DynamoDbSecondarySortKey(indexNames = GSI1_NAME)
  @DynamoDbAttribute(GSI1SK)
  public String getGsi1sk() {
    return gsi1sk;
  }

  public void setGsi1sk(String gsi1sk) {
    this.gsi1sk = gsi1sk;
  }

  @DynamoDbAttribute(USER)
  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
  }

  @DynamoDbAttribute(OPEN_LIBRARY_WORK_ID)
  public String getOpenLibraryWorkId() {
    return openLibraryWorkId;
  }

  public void setOpenLibraryWorkId(String openLibraryWorkId) {
    this.openLibraryWorkId = openLibraryWorkId;
  }

  @DynamoDbAttribute(TITLE)
  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  @DynamoDbAttribute(AUTHORS)
  public List<String> getAuthors() {
    return authors;
  }

  public void setAuthors(List<String> authors) {
    this.authors = authors;
  }

  @DynamoDbAttribute(COVER_URL)
  public String getCoverUrl() {
    return coverUrl;
  }

  public void setCoverUrl(String coverUrl) {
    this.coverUrl = coverUrl;
  }

  @DynamoDbAttribute(PAGE_COUNT)
  public Integer getPageCount() {
    return pageCount;
  }

  public void setPageCount(Integer pageCount) {
    this.pageCount = pageCount;
  }

  @DynamoDbAttribute(PUBLICATION_YEAR)
  public Integer getPublicationYear() {
    return publicationYear;
  }

  public void setPublicationYear(Integer publicationYear) {
    this.publicationYear = publicationYear;
  }

  @DynamoDbAttribute(FINISHED_DATE)
  @DynamoDbConvertedBy(LocalDateConverter.class)
  public LocalDate getFinishedDate() {
    return finishedDate;
  }

  public void setFinishedDate(LocalDate finishedDate) {
    this.finishedDate = finishedDate;
  }

  @DynamoDbAttribute(CREATED_AT)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  @DynamoDbAttribute(UPDATED_AT)
  @DynamoDbConvertedBy(EpochSecondConverter.class)
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    BookTrackerItem that = (BookTrackerItem) o;
    return Objects.equals(pk, that.pk)
        && Objects.equals(sk, that.sk)
        && Objects.equals(gsi1pk, that.gsi1pk)
        && Objects.equals(gsi1sk, that.gsi1sk)
        && Objects.equals(user, that.user)
        && Objects.equals(openLibraryWorkId, that.openLibraryWorkId)
        && Objects.equals(title, that.title)
        && Objects.equals(authors, that.authors)
        && Objects.equals(coverUrl, that.coverUrl)
        && Objects.equals(pageCount, that.pageCount)
        && Objects.equals(publicationYear, that.publicationYear)
        && Objects.equals(finishedDate, that.finishedDate)
        && Objects.equals(createdAt, that.createdAt)
        && Objects.equals(updatedAt, that.updatedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        pk,
        sk,
        gsi1pk,
        gsi1sk,
        user,
        openLibraryWorkId,
        title,
        authors,
        coverUrl,
        pageCount,
        publicationYear,
        finishedDate,
        createdAt,
        updatedAt);
  }

  @Override
  public String toString() {
    return "BookTrackerItem{"
        + "pk='"
        + pk
        + '\''
        + ", sk='"
        + sk
        + '\''
        + ", gsi1pk='"
        + gsi1pk
        + '\''
        + ", gsi1sk='"
        + gsi1sk
        + '\''
        + ", user='"
        + user
        + '\''
        + ", openLibraryWorkId='"
        + openLibraryWorkId
        + '\''
        + ", title='"
        + title
        + '\''
        + ", authors="
        + authors
        + ", coverUrl='"
        + coverUrl
        + '\''
        + ", pageCount="
        + pageCount
        + ", publicationYear="
        + publicationYear
        + ", finishedDate="
        + finishedDate
        + ", createdAt="
        + createdAt
        + ", updatedAt="
        + updatedAt
        + '}';
  }

  public static String formatPk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatSk(String openLibraryWorkId) {
    return BOOK_PREFIX + openLibraryWorkId;
  }

  public static String formatGsi1pk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatGsi1sk(LocalDate finishedDate, String openLibraryWorkId) {
    return FINISHED_PREFIX + finishedDate.toString() + DELIMITER + BOOK_PREFIX + openLibraryWorkId;
  }

  public static BookTrackerItem create(
      String user,
      String openLibraryWorkId,
      String title,
      List<String> authors,
      String coverUrl,
      Integer pageCount,
      Integer publicationYear,
      LocalDate finishedDate,
      Instant createdAt,
      Instant updatedAt) {
    var item = new BookTrackerItem();
    item.setPk(formatPk(user));
    item.setSk(formatSk(openLibraryWorkId));
    item.setGsi1pk(formatGsi1pk(user));
    item.setGsi1sk(formatGsi1sk(finishedDate, openLibraryWorkId));
    item.setUser(user);
    item.setOpenLibraryWorkId(openLibraryWorkId);
    item.setTitle(title);
    item.setAuthors(authors);
    item.setCoverUrl(coverUrl);
    item.setPageCount(pageCount);
    item.setPublicationYear(publicationYear);
    item.setFinishedDate(finishedDate);
    item.setCreatedAt(createdAt);
    item.setUpdatedAt(updatedAt);
    return item;
  }
}
