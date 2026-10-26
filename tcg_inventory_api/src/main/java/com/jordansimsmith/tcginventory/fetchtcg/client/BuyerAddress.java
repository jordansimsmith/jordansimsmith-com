package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record BuyerAddress(
    @JsonProperty("line1") @Nullable String line1,
    @JsonProperty("line2") @Nullable String line2,
    @JsonProperty("suburb") @Nullable String suburb,
    @JsonProperty("city") @Nullable String city,
    @JsonProperty("post_code") @Nullable String postCode,
    @JsonProperty("country") @Nullable String country) {}
