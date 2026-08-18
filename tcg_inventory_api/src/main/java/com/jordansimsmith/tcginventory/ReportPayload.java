package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportPayload(
    @JsonProperty("totals") @Nullable Totals totals,
    @JsonProperty("top_sets") @Nullable List<TopSet> topSets,
    @JsonProperty("price_buckets") @Nullable List<PriceBucket> priceBuckets,
    @JsonProperty("top_hits") @Nullable List<TopHit> topHits,
    @JsonProperty("aging_bands") @Nullable List<AgingBand> agingBands,
    @JsonProperty("revenue_by_month") @Nullable List<RevenueByMonth> revenueByMonth,
    @JsonProperty("intake_vs_sales_by_week") @Nullable
        List<IntakeVsSalesByWeek> intakeVsSalesByWeek) {

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

  public record TopHit(
      @JsonProperty("sku_id") String skuId,
      @JsonProperty("name") String name,
      @JsonProperty("set_code") String setCode,
      @JsonProperty("collector_number") String collectorNumber,
      @JsonProperty("finish") String finish,
      @JsonProperty("condition") String condition,
      @JsonProperty("price") String price,
      @JsonProperty("in_stock_units") int inStockUnits) {}

  public record AgingBand(
      @JsonProperty("label") String label, @JsonProperty("in_stock_units") int inStockUnits) {}

  public record RevenueByMonth(
      @JsonProperty("month") String month,
      @JsonProperty("revenue") String revenue,
      @JsonProperty("order_count") int orderCount) {}

  public record IntakeVsSalesByWeek(
      @JsonProperty("week_start") String weekStart,
      @JsonProperty("added_units") int addedUnits,
      @JsonProperty("sold_units") int soldUnits) {}
}
