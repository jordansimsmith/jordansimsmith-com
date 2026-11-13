package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeleteScanRowRequest(
    @JsonProperty("scan_id") String scanId, @JsonProperty("scan_position") int scanPosition) {}
