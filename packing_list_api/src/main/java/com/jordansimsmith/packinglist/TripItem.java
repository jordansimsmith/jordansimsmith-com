package com.jordansimsmith.packinglist;

import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;

@DynamoDbBean
public class TripItem {
  public static final String NAME = "name";
  public static final String CATEGORY = "category";
  public static final String QUANTITY = "quantity";
  public static final String TAGS = "tags";
  public static final String STATUS = "status";

  private String name;
  private String category;
  private int quantity;
  private List<String> tags;
  private TripItemStatus status;

  @DynamoDbAttribute(NAME)
  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  @DynamoDbAttribute(CATEGORY)
  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  @DynamoDbAttribute(QUANTITY)
  public int getQuantity() {
    return quantity;
  }

  public void setQuantity(int quantity) {
    this.quantity = quantity;
  }

  @DynamoDbAttribute(TAGS)
  public List<String> getTags() {
    return tags;
  }

  public void setTags(List<String> tags) {
    this.tags = tags;
  }

  @DynamoDbAttribute(STATUS)
  @DynamoDbConvertedBy(TripItemStatusConverter.class)
  public TripItemStatus getStatus() {
    return status;
  }

  public void setStatus(TripItemStatus status) {
    this.status = status;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TripItem tripItem = (TripItem) o;
    return quantity == tripItem.quantity
        && Objects.equals(name, tripItem.name)
        && Objects.equals(category, tripItem.category)
        && Objects.equals(tags, tripItem.tags)
        && status == tripItem.status;
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, category, quantity, tags, status);
  }

  @Override
  public String toString() {
    return "TripItem{"
        + "name='"
        + name
        + '\''
        + ", category='"
        + category
        + '\''
        + ", quantity="
        + quantity
        + ", tags="
        + tags
        + ", status="
        + status
        + '}';
  }

  public static TripItem create(
      String name, String category, int quantity, List<String> tags, TripItemStatus status) {
    var item = new TripItem();
    item.setName(name);
    item.setCategory(category);
    item.setQuantity(quantity);
    item.setTags(tags);
    item.setStatus(status);
    return item;
  }
}
