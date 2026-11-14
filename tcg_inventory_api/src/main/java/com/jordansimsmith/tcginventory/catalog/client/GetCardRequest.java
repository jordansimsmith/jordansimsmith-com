package com.jordansimsmith.tcginventory.catalog.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetCardRequest(
    @JsonProperty("game") String game, @JsonProperty("external_id") String externalId) {}
