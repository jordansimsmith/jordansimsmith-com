package com.jordansimsmith.tcginventory.reports.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record GetReportResponse(
    @JsonProperty("generated_at") long generatedAt,
    @JsonProperty("stale") boolean stale,
    @JsonProperty("generation") @Nullable ReportGeneration generation,
    @JsonProperty("report") ReportPayload report) {}
