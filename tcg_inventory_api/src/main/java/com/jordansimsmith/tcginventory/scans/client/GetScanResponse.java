package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record GetScanResponse(
    @JsonProperty("scan_id") String scanId,
    @JsonProperty("game") String game,
    @JsonProperty("status") ScanStatus status,
    @JsonProperty("condition") String condition,
    @JsonProperty("finish") String finish,
    @JsonProperty("row_count") int rowCount,
    @JsonProperty("import_id") @Nullable String importId,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("rows") List<ScanRow> rows) {}
