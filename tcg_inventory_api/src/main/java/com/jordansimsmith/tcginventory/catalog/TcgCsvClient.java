package com.jordansimsmith.tcginventory.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

public interface TcgCsvClient {
  record UpdateMarker(String sourceMarker, Instant updatedAt) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Group(
      @JsonProperty("groupId") int groupId,
      @JsonProperty("categoryId") int categoryId,
      @JsonProperty("name") String name,
      @JsonProperty("abbreviation") String abbreviation) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Product(
      @JsonProperty("productId") int productId,
      @JsonProperty("categoryId") int categoryId,
      @JsonProperty("groupId") int groupId,
      @JsonProperty("name") String name,
      @JsonProperty("extendedData") List<ExtendedData> extendedData) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ExtendedData(@JsonProperty("name") String name, @JsonProperty("value") String value) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Price(
      @JsonProperty("productId") int productId, @JsonProperty("subTypeName") String subTypeName) {}

  UpdateMarker getUpdateMarker() throws IOException, InterruptedException;

  List<Group> findGroups(int categoryId) throws IOException, InterruptedException;

  List<Product> findProducts(int categoryId, int groupId) throws IOException, InterruptedException;

  List<Price> findPrices(int categoryId, int groupId) throws IOException, InterruptedException;
}
