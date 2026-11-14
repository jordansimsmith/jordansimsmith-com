package com.jordansimsmith.tcginventory.catalog.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record SearchCardsRequest(
    @JsonProperty("game") String game,
    @JsonProperty("query") String query,
    @JsonProperty("finish") String finish,
    @JsonProperty("limit") @Nullable Integer limit,
    @JsonProperty("continuation") @Nullable String continuation) {}
