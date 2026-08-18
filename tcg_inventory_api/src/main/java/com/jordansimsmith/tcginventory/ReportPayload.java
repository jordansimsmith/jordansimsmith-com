package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportPayload(
    @JsonProperty("totals") @Nullable Totals totals,
    @JsonProperty("top_sets") @Nullable List<TopSet> topSets,
    @JsonProperty("price_buckets") @Nullable List<PriceBucket> priceBuckets) {

  public record Totals(
      @JsonProperty("inventory_value") String inventoryValue,
      @JsonProperty("in_stock_units") int inStockUnits,
      @JsonProperty("sku_count") int skuCount,
      @JsonProperty("reserved_units") int reservedUnits,
      @JsonProperty("sold_units") int soldUnits,
      @JsonProperty("revenue_to_date") String revenueToDate,
      @JsonProperty("unpriced_units") int unpricedUnits) {}

  public record TopSet(
      @JsonProperty("set_code") String setCode,
      @JsonProperty("set_name") String setName,
      @JsonProperty("in_stock_units") int inStockUnits) {}

  public record PriceBucket(
      @JsonProperty("label") String label, @JsonProperty("in_stock_units") int inStockUnits) {}
}
