package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ScanSelection(
    @JsonProperty("scan_position") int scanPosition,
    @JsonProperty("external_id") String externalId) {}
