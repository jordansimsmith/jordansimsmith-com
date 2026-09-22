package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ScanMessage(
    @JsonProperty("user") String user, @JsonProperty("scan_id") String scanId) {}
