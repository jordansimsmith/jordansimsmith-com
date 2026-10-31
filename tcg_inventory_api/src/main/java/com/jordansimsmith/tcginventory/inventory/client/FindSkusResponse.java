package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record FindSkusResponse(
    @JsonProperty("skus") List<Sku> skus,
    @JsonProperty("next_continuation") @Nullable String nextContinuation) {}
