package com.jordansimsmith.tcginventory.reports.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record ReportGeneration(
    @JsonProperty("status") ReportGenerationStatus status,
    @JsonProperty("error") @Nullable String error,
    @JsonProperty("started_at") long startedAt,
    @JsonProperty("finished_at") @Nullable Long finishedAt) {}
