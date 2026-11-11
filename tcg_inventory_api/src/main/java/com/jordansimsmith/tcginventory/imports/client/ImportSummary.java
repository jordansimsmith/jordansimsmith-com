package com.jordansimsmith.tcginventory.imports.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record ImportSummary(
    @JsonProperty("import_id") String importId,
    @JsonProperty("game") String game,
    @JsonProperty("filename") @Nullable String filename,
    @JsonProperty("status") ImportStatus status,
    @JsonProperty("row_count") int rowCount,
    @JsonProperty("created_at") long createdAt) {}
