package com.jordansimsmith.tcginventory.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

@JsonPropertyOrder({
  "schema_version",
  "game",
  "external_source",
  "catalog_source",
  "category_id",
  "snapshot_id",
  "source_marker",
  "source_updated_at",
  "created_at",
  "groups",
  "products"
})
public record TcgCsvCatalogSnapshot(
    @JsonProperty("schema_version") int schemaVersion,
    @JsonProperty("game") String game,
    @JsonProperty("external_source") String externalSource,
    @JsonProperty("catalog_source") String catalogSource,
    @JsonProperty("category_id") int categoryId,
    @JsonProperty("snapshot_id") String snapshotId,
    @JsonProperty("source_marker") String sourceMarker,
    @JsonProperty("source_updated_at") long sourceUpdatedAt,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("groups") List<Group> groups,
    @JsonProperty("products") List<Product> products) {
  public TcgCsvCatalogSnapshot {
    groups = List.copyOf(groups);
    products = List.copyOf(products);
  }

  @JsonPropertyOrder({"group_id", "set_code", "set_name"})
  public record Group(
      @JsonProperty("group_id") int groupId,
      @JsonProperty("set_code") String setCode,
      @JsonProperty("set_name") String setName) {}

  @JsonPropertyOrder({"product_id", "name", "group_id", "collector_number", "available_finishes"})
  public record Product(
      @JsonProperty("product_id") int productId,
      @JsonProperty("name") String name,
      @JsonProperty("group_id") int groupId,
      @JsonProperty("collector_number") String collectorNumber,
      @JsonProperty("available_finishes") List<String> availableFinishes) {
    public Product {
      availableFinishes = List.copyOf(availableFinishes);
    }
  }
}
