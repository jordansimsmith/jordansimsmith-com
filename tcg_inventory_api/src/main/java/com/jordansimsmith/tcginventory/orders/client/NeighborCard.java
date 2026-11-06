package com.jordansimsmith.tcginventory.orders.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NeighborCard(
    @JsonProperty("name") String name,
    @JsonProperty("set_code") String setCode,
    @JsonProperty("collector_number") String collectorNumber,
    @JsonProperty("finish") String finish,
    @JsonProperty("condition") String condition) {}
