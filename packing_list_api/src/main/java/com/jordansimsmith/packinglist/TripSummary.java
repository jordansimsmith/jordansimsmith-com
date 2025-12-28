package com.jordansimsmith.packinglist;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TripSummary(
    @JsonProperty("trip_id") String tripId,
    @JsonProperty("name") String name,
    @JsonProperty("destination") String destination,
    @JsonProperty("departure_date") String departureDate,
    @JsonProperty("return_date") String returnDate,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("updated_at") long updatedAt) {}
