package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ScanSuggestion(
    @JsonProperty("external_id") String externalId,
    @JsonProperty("name") String name,
    @JsonProperty("score") double score) {}
