package com.jordansimsmith.packinglist;

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
public class PackingListItem {
  public static final String DELIMITER = "#";
  public static final String USER_PREFIX = "USER" + DELIMITER;
  public static final String TRIP_PREFIX = "TRIP" + DELIMITER;
  public static final String DEPARTURE_PREFIX = "DEPARTURE" + DELIMITER;

  public static final String TABLE_NAME = "packing_list";
  public static final String GSI1_NAME = "gsi1";

  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String GSI1PK = "gsi1pk";
  public static final String GSI1SK = "gsi1sk";
  public static final String USER = "user";
  public static final String TRIP_ID = "trip_id";
  public static final String NAME = "name";
  public static final String DESTINATION = "destination";
  public static final String DEPARTURE_DATE = "departure_date";
  public static final String RETURN_DATE = "return_date";
  public static final String ITEMS = "items";
  public static final String CREATED_AT = "created_at";
  public static final String UPDATED_AT = "updated_at";

  private String pk;
  private String sk;
  private String gsi1pk;
  private String gsi1sk;
  private String user;
  private String tripId;
  private String name;
  private String destination;
  private LocalDate departureDate;
  private LocalDate returnDate;
  private List<TripItem> items;
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

  @DynamoDbAttribute(TRIP_ID)
  public String getTripId() {
    return tripId;
  }

  public void setTripId(String tripId) {
    this.tripId = tripId;
  }

  @DynamoDbAttribute(NAME)
  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  @DynamoDbAttribute(DESTINATION)
  public String getDestination() {
    return destination;
  }

  public void setDestination(String destination) {
    this.destination = destination;
  }

  @DynamoDbAttribute(DEPARTURE_DATE)
  @DynamoDbConvertedBy(LocalDateConverter.class)
  public LocalDate getDepartureDate() {
    return departureDate;
  }

  public void setDepartureDate(LocalDate departureDate) {
    this.departureDate = departureDate;
  }

  @DynamoDbAttribute(RETURN_DATE)
  @DynamoDbConvertedBy(LocalDateConverter.class)
  public LocalDate getReturnDate() {
    return returnDate;
  }

  public void setReturnDate(LocalDate returnDate) {
    this.returnDate = returnDate;
  }

  @DynamoDbAttribute(ITEMS)
  public List<TripItem> getItems() {
    return items;
  }

  public void setItems(List<TripItem> items) {
    this.items = items;
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
    PackingListItem that = (PackingListItem) o;
    return Objects.equals(pk, that.pk)
        && Objects.equals(sk, that.sk)
        && Objects.equals(gsi1pk, that.gsi1pk)
        && Objects.equals(gsi1sk, that.gsi1sk)
        && Objects.equals(user, that.user)
        && Objects.equals(tripId, that.tripId)
        && Objects.equals(name, that.name)
        && Objects.equals(destination, that.destination)
        && Objects.equals(departureDate, that.departureDate)
        && Objects.equals(returnDate, that.returnDate)
        && Objects.equals(items, that.items)
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
        tripId,
        name,
        destination,
        departureDate,
        returnDate,
        items,
        createdAt,
        updatedAt);
  }

  @Override
  public String toString() {
    return "PackingListItem{"
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
        + ", tripId='"
        + tripId
        + '\''
        + ", name='"
        + name
        + '\''
        + ", destination='"
        + destination
        + '\''
        + ", departureDate='"
        + departureDate
        + '\''
        + ", returnDate='"
        + returnDate
        + '\''
        + ", items="
        + items
        + ", createdAt="
        + createdAt
        + ", updatedAt="
        + updatedAt
        + '}';
  }

  public static String formatPk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatSk(String tripId) {
    return TRIP_PREFIX + tripId;
  }

  public static String formatGsi1pk(String user) {
    return USER_PREFIX + user;
  }

  public static String formatGsi1sk(LocalDate departureDate, String tripId) {
    return DEPARTURE_PREFIX + departureDate.toString() + DELIMITER + TRIP_PREFIX + tripId;
  }

  public static PackingListItem create(
      String user,
      String tripId,
      String name,
      String destination,
      LocalDate departureDate,
      LocalDate returnDate,
      List<TripItem> items,
      Instant createdAt,
      Instant updatedAt) {
    var item = new PackingListItem();
    item.setPk(formatPk(user));
    item.setSk(formatSk(tripId));
    item.setGsi1pk(formatGsi1pk(user));
    item.setGsi1sk(formatGsi1sk(departureDate, tripId));
    item.setUser(user);
    item.setTripId(tripId);
    item.setName(name);
    item.setDestination(destination);
    item.setDepartureDate(departureDate);
    item.setReturnDate(returnDate);
    item.setItems(items);
    item.setCreatedAt(createdAt);
    item.setUpdatedAt(updatedAt);
    return item;
  }
}
