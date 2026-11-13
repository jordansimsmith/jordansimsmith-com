package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record IdentifyScanRequest(@JsonProperty("scan_id") String scanId) {}
