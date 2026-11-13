package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record ScanRow(
    @JsonProperty("scan_position") int scanPosition,
    @JsonProperty("filename") String filename,
    @JsonProperty("size_bytes") long sizeBytes,
    @JsonProperty("uploaded") boolean uploaded,
    @JsonProperty("status") @Nullable ScanRowStatus status,
    @JsonProperty("needs_review") boolean needsReview,
    @JsonProperty("suggestions") List<ScanSuggestion> suggestions,
    @JsonProperty("source_url") @Nullable String sourceUrl) {}
