package com.jordansimsmith.tcginventory.scans;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ScanMessage(
    @JsonProperty("user") String user, @JsonProperty("scan_id") String scanId) {}
