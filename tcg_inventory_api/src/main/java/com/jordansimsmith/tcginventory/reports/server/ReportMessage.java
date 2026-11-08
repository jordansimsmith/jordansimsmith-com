package com.jordansimsmith.tcginventory.reports.server;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReportMessage(
    @JsonProperty("user") String user, @JsonProperty("generation_id") String generationId) {}
