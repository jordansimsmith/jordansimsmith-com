package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import javax.annotation.Nullable;

public class OrderLines {
  public record OrderLine(
      @JsonProperty("sku_id") String skuId,
      @JsonProperty("fetchtcg_listing_id") int fetchtcgListingId,
      @JsonProperty("quantity") int quantity,
      @JsonProperty("price") String price,
      @JsonProperty("listed_price") @Nullable String listedPrice,
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

  public static BigDecimal itemsTotal(List<OrderLine> lines) {
    var total = BigDecimal.ZERO;
    for (var line : lines) {
      if (line.price() != null) {
        total = total.add(new BigDecimal(line.price()));
      }
    }
    return total;
  }

  @Nullable
  public static String itemsTotalPrice(List<OrderLine> lines) {
    if (lines.isEmpty()) {
      return null;
    }
    return itemsTotal(lines).toPlainString();
  }

  @Nullable
  public static String listedTotalPrice(List<OrderLine> lines) {
    if (lines.isEmpty()) {
      return null;
    }
    var total = BigDecimal.ZERO;
    for (var line : lines) {
      if (line.listedPrice() == null) {
        return null;
      }
      total =
          total.add(
              new BigDecimal(line.listedPrice()).multiply(BigDecimal.valueOf(line.quantity())));
    }
    return total.toPlainString();
  }
}
