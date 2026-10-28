package com.jordansimsmith.tcginventory.games.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record Game(
    @JsonProperty("id") String id,
    @JsonProperty("display_name") String displayName,
    @JsonProperty("external_source") String externalSource,
    @JsonProperty("scanning_enabled") boolean scanningEnabled,
    @JsonProperty("csv_import_enabled") boolean csvImportEnabled,
    @JsonProperty("finishes") List<Finish> finishes,
    @JsonProperty("scan_review_image_regions")
        List<ScanReviewImageRegion> scanReviewImageRegions) {}
