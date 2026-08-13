package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

public class OrderLines {
  public record OrderLine(
      @JsonProperty("sku_id") String skuId,
      @JsonProperty("fetchtcg_listing_id") int fetchtcgListingId,
      @JsonProperty("quantity") int quantity,
      @JsonProperty("price") String price,
      @JsonProperty("allocated_sequence_numbers") List<Integer> allocatedSequenceNumbers) {}

  public static List<OrderLine> parse(String linesJson, ObjectMapper objectMapper) {
    if (linesJson == null || linesJson.isEmpty()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(linesJson, new TypeReference<List<OrderLine>>() {});
    } catch (Exception e) {
      throw new RuntimeException("failed to parse order lines", e);
    }
  }
}
