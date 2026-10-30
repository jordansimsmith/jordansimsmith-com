package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record Unit(
    @JsonProperty("sequence_number") int sequenceNumber,
    @JsonProperty("location") String location,
    @JsonProperty("status") UnitStatus status,
    @JsonProperty("photos") List<String> photos,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("updated_at") @Nullable Long updatedAt) {}
