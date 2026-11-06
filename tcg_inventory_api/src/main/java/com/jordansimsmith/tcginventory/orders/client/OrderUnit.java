package com.jordansimsmith.tcginventory.orders.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record OrderUnit(
    @JsonProperty("game") String game,
    @JsonProperty("sequence_number") int sequenceNumber,
    @JsonProperty("location") @Nullable String location,
    @JsonProperty("current_location") @Nullable String currentLocation,
    @JsonProperty("external_id") String externalId,
    @JsonProperty("name") String name,
    @JsonProperty("set_code") String setCode,
    @JsonProperty("collector_number") String collectorNumber,
    @JsonProperty("finish") String finish,
    @JsonProperty("condition") String condition,
    @JsonProperty("price") @Nullable String price,
    @JsonProperty("previous_card") @Nullable NeighborCard previousCard,
    @JsonProperty("next_card") @Nullable NeighborCard nextCard) {}
