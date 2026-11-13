package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CreateScanResponse(
    @JsonProperty("scan_id") String scanId, @JsonProperty("rows") List<ScanRowUpload> rows) {}
