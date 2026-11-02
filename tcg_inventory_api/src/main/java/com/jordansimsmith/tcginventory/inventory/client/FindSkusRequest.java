package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record FindSkusRequest(
    @JsonProperty("game") @Nullable String game,
    @JsonProperty("search") @Nullable String search,
    @JsonProperty("dirty") @Nullable Boolean dirty,
    @JsonProperty("limit") @Nullable Integer limit,
    @JsonProperty("continuation") @Nullable String continuation) {}
