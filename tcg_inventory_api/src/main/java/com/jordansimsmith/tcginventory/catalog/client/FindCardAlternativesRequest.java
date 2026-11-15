package com.jordansimsmith.tcginventory.catalog.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record FindCardAlternativesRequest(
    @JsonProperty("game") String game,
    @JsonProperty("external_id") String externalId,
    @JsonProperty("finish") String finish,
    @JsonProperty("limit") @Nullable Integer limit,
    @JsonProperty("continuation") @Nullable String continuation) {}
