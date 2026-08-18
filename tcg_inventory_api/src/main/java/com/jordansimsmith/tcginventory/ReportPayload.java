package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportPayload(
    @JsonProperty("totals") @Nullable ReportTotals totals,
    @JsonProperty("top_sets") @Nullable List<ReportTopSet> topSets) {}
