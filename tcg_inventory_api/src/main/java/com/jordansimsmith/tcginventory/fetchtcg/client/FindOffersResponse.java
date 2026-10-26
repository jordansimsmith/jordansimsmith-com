package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record FindOffersResponse(
    @JsonProperty("offers") List<Offer> offers,
    @JsonProperty("next_continuation") @Nullable String nextContinuation) {}
