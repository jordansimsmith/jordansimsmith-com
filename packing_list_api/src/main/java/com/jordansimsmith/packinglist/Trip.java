package com.jordansimsmith.packinglist;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record Trip(
    @JsonProperty("trip_id") String tripId,
    @JsonProperty("name") String name,
    @JsonProperty("destination") String destination,
    @JsonProperty("departure_date") String departureDate,
    @JsonProperty("return_date") String returnDate,
    @JsonProperty("items") List<Item> items,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("updated_at") long updatedAt) {

  public record Item(
      @JsonProperty("name") String name,
      @JsonProperty("category") String category,
      @JsonProperty("quantity") int quantity,
      @JsonProperty("tags") List<String> tags,
      @JsonProperty("status") String status) {}
}
