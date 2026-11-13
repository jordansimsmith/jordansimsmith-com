package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ConfirmScanRequest(
    @JsonProperty("scan_id") String scanId, @JsonProperty("rows") List<ScanSelection> rows) {}
