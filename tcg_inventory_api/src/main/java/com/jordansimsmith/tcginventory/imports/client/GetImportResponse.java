package com.jordansimsmith.tcginventory.imports.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record GetImportResponse(
    @JsonProperty("import_id") String importId,
    @JsonProperty("game") String game,
    @JsonProperty("filename") @Nullable String filename,
    @JsonProperty("status") ImportStatus status,
    @JsonProperty("row_count") int rowCount,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("total_suggested_price") String totalSuggestedPrice,
    @JsonProperty("unit_count") @Nullable Integer unitCount,
    @JsonProperty("placement_instructions") @Nullable
        List<PlacementInstruction> placementInstructions,
    @JsonProperty("rows") List<ImportRow> rows) {}
