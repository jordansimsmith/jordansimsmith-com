package com.jordansimsmith.tcginventory.scans.server;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ScanConfirmationMessage(
    @JsonProperty("user") String user, @JsonProperty("scan_id") String scanId) {}
