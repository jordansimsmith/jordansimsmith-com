package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record Unit(
    @JsonProperty("sequence_number") int sequenceNumber,
    @JsonProperty("location") String location,
    @JsonProperty("status") String status,
    @JsonProperty("photos") List<String> photos) {}
