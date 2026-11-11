package com.jordansimsmith.tcginventory.imports.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PlacementInstruction(
    @JsonProperty("block") String block,
    @JsonProperty("from_location") String fromLocation,
    @JsonProperty("to_location") String toLocation,
    @JsonProperty("from_name") String fromName,
    @JsonProperty("to_name") String toName,
    @JsonProperty("unit_count") int unitCount) {}
