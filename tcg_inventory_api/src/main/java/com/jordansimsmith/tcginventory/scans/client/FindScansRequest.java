package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record FindScansRequest(
    @JsonProperty("limit") @Nullable Integer limit,
    @JsonProperty("continuation") @Nullable String continuation) {}
