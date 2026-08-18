package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReportTopSet(
    @JsonProperty("set_code") String setCode,
    @JsonProperty("set_name") String setName,
    @JsonProperty("in_stock_units") int inStockUnits) {}
