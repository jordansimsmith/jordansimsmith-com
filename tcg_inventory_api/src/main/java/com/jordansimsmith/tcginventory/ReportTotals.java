package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReportTotals(
    @JsonProperty("inventory_value") String inventoryValue,
    @JsonProperty("in_stock_units") int inStockUnits,
    @JsonProperty("sku_count") int skuCount,
    @JsonProperty("reserved_units") int reservedUnits,
    @JsonProperty("sold_units") int soldUnits,
    @JsonProperty("revenue_to_date") String revenueToDate,
    @JsonProperty("unpriced_units") int unpricedUnits) {}
