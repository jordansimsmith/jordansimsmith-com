package com.jordansimsmith.tcginventory.catalog.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record ImageUrls(
    @JsonProperty("small") @Nullable String small,
    @JsonProperty("normal") @Nullable String normal) {}
